package ingest;

import org.apache.avro.generic.GenericRecord;

public class ShardInfo {
  protected String rideStatus;
  protected String date;

  public ShardInfo(GenericRecord r) {
    this.rideStatus = r.get("ride_status").toString();
    this.date = r.get("row_timestamp").toString().substring(0, 10); //2020-10-24T05:52:52.81406-04:00
  }

  public ShardInfo() {
  }
}
