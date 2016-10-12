package com.hortonworks.streaming.impl.kafka;

import org.apache.storm.spout.Scheme;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Values;
import org.apache.log4j.Logger;

import java.io.UnsupportedEncodingException;
import java.sql.Timestamp;
import java.util.List;
import java.nio.ByteBuffer;

public class TruckScheme2 implements Scheme {

  private static final long serialVersionUID = -2990121166902741545L;

  //private static final Logger LOG = Logger.getLogger(TruckScheme2.class);

  @Override
  public List<Object> deserialize(ByteBuffer bytes) {
    try {
      String truckEvent = new String(bytes.array(), "UTF-8");
      String[] pieces = truckEvent.split("\\|");

      Timestamp eventTime = Timestamp.valueOf(pieces[0]);
      int truckId = Integer.valueOf(pieces[1]);
      int driverId = Integer.valueOf(pieces[2]);
      String driverName = pieces[3];
      int routeId = Integer.valueOf(pieces[4]);
      String routeName = pieces[5];
      String eventType = pieces[6];
      double latitude = Double.valueOf(pieces[7]);
      double longitude = Double.valueOf(pieces[8]);
      long correlationId = Long.valueOf(pieces[9]);
      String eventKey = consructKey(driverId, truckId, eventTime);

      //LOG.info("Creating a Truck Scheme with driverId[" + driverId + "], driverName[" + driverName + "], routeId[" +
          //routeId + "], routeName[" + routeName + "], truckEvent[" + truckEvent + "], and correlationId[" +
          //correlationId + "]");
      return new Values(driverId, truckId, eventTime, eventType, longitude, latitude, eventKey, correlationId,
          driverName, routeId, routeName);

    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }

  }

  @Override
  public Fields getOutputFields() {
    return new Fields("driverId", "truckId", "eventTime", "eventType", "longitude", "latitude", "eventKey",
        "correlationId", "driverName", "routeId", "routeName");

  }

  private String consructKey(int driverId, int truckId, Timestamp ts2) {
    long reverseTime = Long.MAX_VALUE - ts2.getTime();
    String rowKey = driverId + "|" + truckId + "|" + reverseTime;
    return rowKey;
  }

}
