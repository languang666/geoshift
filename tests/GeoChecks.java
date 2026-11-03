import app.geoshift.Geo;
import app.geoshift.MapSource;
import java.util.Random;

public class GeoChecks {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    public static void main(String[] args){
        check(Geo.distance(0,0,0,0)==0);
        check(Math.abs(Geo.distance(0,0,0,1)-111195)<2);
        check(Math.abs(Geo.bearing(0,0,1,0))<0.0001);
        check(Math.abs(Geo.bearing(0,0,0,1)-90)<0.0001);
        check(!Geo.valid(Double.NaN,0));check(!Geo.valid(0,181));
        double[] crossed=Geo.interpolate(0,179,0,-179,0.5);
        check(Math.abs(Math.abs(crossed[1])-180)<0.0001);
        double[] outside=Geo.wgs84ToGcj02(51.5,-0.12);
        check(outside[0]==51.5&&outside[1]==-0.12);
        Random random=new Random(923);
        check(MapSource.fromKey("amap") == MapSource.AMAP);
        check(MapSource.fromKey("osm") == MapSource.DEFAULT);
        check(MapSource.fromKey("unknown") == MapSource.DEFAULT);
        check(MapSource.fromKey(null) == MapSource.DEFAULT);
        double[] beijing = MapSource.AMAP.toDisplay(39.9042, 116.4074);
        check(Geo.distance(39.9042,116.4074,beijing[0],beijing[1])>100);
        check(Geo.distance(39.9042,116.4074,beijing[0],beijing[1])<1000);
        for (MapSource source : MapSource.values()) {
            double[] london = source.toDisplay(51.5,-0.12);
            check(london[0]==51.5 && london[1]==-0.12);
            for (double[] position : new double[][]{{39.9042,116.4074},{31.2304,121.4737},{22.5431,114.0579},{30.5728,104.0668}}) {
                double lat=position[0],lon=position[1];
                for (int switches=0;switches<50;switches++) {
                    double[] display=source.toDisplay(lat,lon);
                    if(source==MapSource.DEFAULT) check(display[0]==lat && display[1]==lon);
                    double[] stored=source.toWgs(display[0],display[1]);
                    lat=stored[0];lon=stored[1];
                }
                check(Geo.distance(position[0],position[1],lat,lon)<0.2);
            }
        }
        for(int i=0;i<300;i++){
            double lat=20+random.nextDouble()*25,lon=90+random.nextDouble()*30;
            double[] gcj=Geo.wgs84ToGcj02(lat,lon),wgs=Geo.gcj02ToWgs84(gcj[0],gcj[1]);
            check(Geo.distance(lat,lon,wgs[0],wgs[1])<0.2);
            double distance=1+random.nextDouble()*10000,bearing=random.nextDouble()*360;
            double[] moved=Geo.move(lat,lon,distance,bearing);
            check(Math.abs(Geo.distance(lat,lon,moved[0],moved[1])-distance)<0.001);
            check(Geo.valid(moved[0],moved[1]));
        }
        try{Geo.move(0,0,-1,90);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        System.out.println("PASS: "+checks+" checks on the new Geo source; no Android or network required.");
    }
}
