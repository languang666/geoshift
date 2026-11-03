package android.location;
import android.os.Bundle;
public class Location {
    private final String provider;
    private Bundle extras;
    private double lat,lon,alt;
    private float accuracy,speed,bearing;
    private long time,elapsed;
    private boolean mock;
    public Location(String provider){this.provider=provider;}
    public String getProvider(){return provider;}
    public double getLatitude(){return lat;} public void setLatitude(double v){lat=v;}
    public double getLongitude(){return lon;} public void setLongitude(double v){lon=v;}
    public double getAltitude(){return alt;} public void setAltitude(double v){alt=v;}
    public float getAccuracy(){return accuracy;} public void setAccuracy(float v){accuracy=v;}
    public float getSpeed(){return speed;} public void setSpeed(float v){speed=v;}
    public float getBearing(){return bearing;} public void setBearing(float v){bearing=v;}
    public long getTime(){return time;} public void setTime(long v){time=v;}
    public long getElapsedRealtimeNanos(){return elapsed;} public void setElapsedRealtimeNanos(long v){elapsed=v;}
    public boolean isMock(){return mock;} public void setMock(boolean v){mock=v;}
    public boolean isFromMockProvider(){return mock;}
    public void setVerticalAccuracyMeters(float v){} public void setSpeedAccuracyMetersPerSecond(float v){} public void setBearingAccuracyDegrees(float v){}
    public Bundle getExtras(){return extras;}
}
