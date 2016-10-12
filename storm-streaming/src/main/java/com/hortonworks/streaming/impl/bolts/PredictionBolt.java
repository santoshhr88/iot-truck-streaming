package com.hortonworks.streaming.impl.bolts;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.IRichBolt;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import com.google.common.primitives.Doubles;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.log4j.Logger;
import org.apache.spark.mllib.classification.LogisticRegressionModel;
import org.apache.spark.mllib.linalg.Vector;
import org.apache.spark.mllib.linalg.Vectors;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;


public class PredictionBolt implements IRichBolt {

  //private static final Logger LOG = Logger.getLogger(PredictionBolt.class);
  private String phoenixDriverPath;
  private Driver phoenixDriver;

  private Properties topologyConfig;

  private OutputCollector collector;
  private LogisticRegressionModel model;


  public PredictionBolt(Properties topologyConfig) {
    this.topologyConfig = topologyConfig;
  }


  public void prepare(Map stormConf, TopologyContext context,
                      OutputCollector collector) {
    this.collector = collector;
    model = instantiateSparkModel();
  }


  public void execute(Tuple input) {

    //LOG.info("Entered prediction bolt execute...");
    String eventType = input.getStringByField("eventType");

    double prediction;

    if (eventType.equals("Normal")) {
      double[] predictionParams = enrichEvent(input);
      prediction = model.predict(Vectors.dense(predictionParams));

      //LOG.info("Prediction is: " + prediction);


      String driverName = input.getStringByField("driverName");
      String routeName = input.getStringByField("routeName");
      int truckId = input.getIntegerByField("truckId");
      Timestamp eventTime = (Timestamp) input.getValueByField("eventTime");
      double longitude = input.getDoubleByField("longitude");
      double latitude = input.getDoubleByField("latitude");
      double driverId = input.getIntegerByField("driverId");
      SimpleDateFormat sdf = new SimpleDateFormat();

      collector.emit(input, new Values(
          prediction == 0.0 ? "normal" : "violation",
          driverName,
          routeName,
          driverId,
          truckId,
          sdf.format(new Date(eventTime.getTime())),
          longitude,
          latitude,
          predictionParams[0] == 1 ? "Y" : "N", // driver certification status
          predictionParams[1] == 1 ? "miles" : "hourly", // driver wage plan
          predictionParams[2] * 100,  // hours feature was scaled down by 100
          predictionParams[3] * 1000, // miles feature was scaled down by 1000
          predictionParams[4] == 1 ? "Y" : "N", // foggy weather
          predictionParams[5] == 1 ? "Y" : "N", // rainy weather
          predictionParams[6] == 1 ? "Y" : "N"  // windy weather
      ));

      if (prediction == 1.0) {

        try {
          writePredictionToHDFS(input, predictionParams, prediction);
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException("Couldn't write prediction to hdfs" + e);
        }
      }
    }

    //acknowledge even if there is an error
    collector.ack(input);

  }


  public int getWeek(Tuple input) {
    Timestamp ts = (Timestamp) input.getValueByField("eventTime");
    Calendar cal = Calendar.getInstance();
    try {
      cal.setTime(new Date(ts.getTime()));
    } catch (Exception e) {
      e.printStackTrace();
    }

    return cal.get(Calendar.WEEK_OF_YEAR);

  }


  public double[] enrichEvent(Tuple input) {
    double driverID = input.getIntegerByField("driverId");
    //int week = input.get
    Connection conn = null;
    try {
      //conn = phoenixDriver.connect("jdbc:phoenix:localhost:2181:/hbase-unsecure",new Properties());
      conn = DriverManager.getConnection("jdbc:phoenix:" + topologyConfig.getProperty("hbase.zookeeper.server") +
          ":2181:/hbase-unsecure");
      // input features to spark model
      double certified = 0, wageplan = 0, hours_logged = 0, miles_logged = 0, foggy = 0, rainy = 0, windy = 0;
      // get driver certification status and wage plan from hbase
      ResultSet rst = conn.createStatement().
          executeQuery("select certified, wage_plan from drivers where driverid=" + driverID);

      while (rst.next()) {
        certified = rst.getString(1).equals("Y") ? 1 : 0;
        wageplan = rst.getString(1).equals("miles") ? 1 : 0;
      }
      // get driver fatigue status from timesheet table in hbase
      rst = conn.createStatement().
          executeQuery("select hours_logged, miles_logged from timesheet"
              + " where driverid=" + driverID + " and week=" + getWeek(input));

      while (rst.next()) {
        hours_logged = rst.getInt(1);
        miles_logged = rst.getInt(2);
      }

      System.out.println("HOURS LOGGED " + hours_logged);
      System.out.println("MILES LOGGED " + miles_logged);

      // scale the hours & miles features for spark model
      hours_logged = hours_logged / 100;
      miles_logged = miles_logged / 1000;

      // get weather conditions - currently these are being simulated randomly, with a bias
      // towards more fog for dangerous drivers
      if (driverID == 12) // jamie
        foggy = new Random().nextInt(100) < 50 ? 1 : 0;
      else if (driverID == 11) // george
        foggy = new Random().nextInt(100) < 35 ? 1 : 0;
      else
        foggy = new Random().nextInt(100) < 12 ? 1 : 0;

      rainy = new Random().nextInt(100) < 20 ? 1 : 0;
      windy = new Random().nextInt(100) < 30 ? 1 : 0;

      // return the enriched event
      return new double[]{certified, wageplan, hours_logged, miles_logged, foggy, rainy, windy};

    } catch (Exception e) {
      e.printStackTrace();
      throw new RuntimeException(e);

    } finally {
      try {
        if (conn != null)
          conn.close();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

  }


  private LogisticRegressionModel instantiateSparkModel() {
    Configuration conf = new Configuration();
    conf.set("fs.defaultFS", topologyConfig.getProperty("hdfs.url"));

    double[] sparkModelInfo = null;

    try {
      sparkModelInfo = getSparkModelInfoFromHDFS(new Path(topologyConfig.getProperty("hdfs.url") +
          "/tmp/sparkML_weights"), conf);
    } catch (Exception e) {
      //LOG.error("Couldn't instantiate Spark model in prediction bolt: " + e.getMessage());
      e.printStackTrace();

      throw new RuntimeException(e);
    }

    // all numbers besides the last value are the weights
    double[] weights = Arrays.copyOfRange(sparkModelInfo, 0, sparkModelInfo.length - 1);

    // the last number in the array is the intercept
    double intercept = sparkModelInfo[sparkModelInfo.length - 1];

    Vector weightsV = (Vectors.dense(weights));
    return new LogisticRegressionModel(weightsV, intercept);


  }


  private double[] getSparkModelInfoFromHDFS(Path location, Configuration conf) throws Exception {

    FileSystem fileSystem = FileSystem.get(location.toUri(), conf);
    FileStatus[] files = fileSystem.listStatus(location);

    if (files == null)
      throw new Exception("Couldn't find Spark Truck ML weights at: " + location);

    ArrayList<Double> modelInfo = new ArrayList<Double>();
    for (FileStatus file : files) {

      if (file.getPath().getName().startsWith("_")) {
        continue;
      }

      InputStream stream = fileSystem.open(file.getPath());


      StringWriter writer = new StringWriter();
      IOUtils.copy(stream, writer, "UTF-8");
      String raw = writer.toString();
      for (String str : raw.split("\n")) {
        modelInfo.add(Double.valueOf(str));
      }
    }


    return Doubles.toArray(modelInfo);
  }

  public void writePredictionToHDFS(Tuple input, double[] params, double prediction) throws Exception {

    try {

      Configuration conf = new Configuration();
      conf.set("fs.defaultFS", topologyConfig.getProperty("hdfs.url"));
      FileSystem fs = FileSystem.get(conf);

      Path pt = new Path(topologyConfig.getProperty("hdfs.url") + "/tmp/predictions/" + System.currentTimeMillis());

      BufferedWriter br = new BufferedWriter(new OutputStreamWriter(fs.create(pt, true)));

      br.write("Original Event: " + input + "\n");
      br.write("\n");
      br.write("Certification status (from HBase): " + (params[0] == 1 ? "Y" : "N") + "\n");
      br.write("Wage plan (from HBase): " + (params[1] == 1 ? "Miles" : "Hours" + "\n"));
      br.write("Hours logged (from HBase): " + params[2] * 100 + "\n");
      br.write("Miles logged (from HBase): " + params[3] * 1000 + "\n");
      br.write("\n");
      br.write("Is Foggy? (from weather API): " + (params[4] == 1 ? "Y" : "N" + "\n"));
      br.write("Is Rainy? (from weather API): " + (params[5] == 1 ? "Y" : "N" + "\n"));
      br.write("Is Windy? (from weather API): " + (params[6] == 1 ? "Y" : "N" + "\n"));
      br.write("\n");
      br.write("\n");
      br.write("Input to Spark ML model: " + Arrays.toString(params) + "\n");
      br.write("\n");
      br.write("Prediction from Spark ML model: " + prediction + "\n");
      br.flush();
      br.close();


    } catch (Exception e) {
      e.printStackTrace();
    }
  }


  /**
   * We don't need to set any configuration because at deployment time, it should pick up all configuration from
   * hbase-site.xml
   * as long as it in classpath. Note that we store hbase-site.xml in src/main/resources so it will be in the
   * topology jar that gets deployed
   *
   * @return
   */


  public void declareOutputFields(OutputFieldsDeclarer declarer) {
    declarer.declare(new Fields("prediction", "driverName",
        "routeName", "driverId", "truckId", "timeStamp",
        "longitude", "latitude", "certified",
        "wagePlan", "hours_logged", "miles_logged",
        "isFoggy", "isRainy", "isWindy"));
  }


  public Map<String, Object> getComponentConfiguration() {
    return null;
  }


  public void cleanup() {
  }


}




