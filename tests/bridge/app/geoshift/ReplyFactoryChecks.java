package app.geoshift;
import java.util.*;
public final class ReplyFactoryChecks {
    public static final class Slice {
        final List<?> values;
        private Slice(List<?> values){this.values=values;}
    }
    public static void main(String[] args)throws Exception {
        Object list=ReplyFactory.emptyList(List.class);
        if(!(list instanceof List)||!((List<?>)list).isEmpty())throw new AssertionError("List reply");
        Object concrete=ReplyFactory.emptyList(ArrayList.class);
        if(!(concrete instanceof ArrayList)||!((List<?>)concrete).isEmpty())throw new AssertionError("concrete list reply");
        Object slice=ReplyFactory.emptyList(Slice.class);
        if(!(slice instanceof Slice)||!((Slice)slice).values.isEmpty())throw new AssertionError("Android-style List constructor");
        try{ReplyFactory.emptyList(String.class);throw new AssertionError("unsupported type accepted");}catch(ReflectiveOperationException expected){}
        System.out.println("PASS: 4 list-reply compatibility checks.");
    }
}
