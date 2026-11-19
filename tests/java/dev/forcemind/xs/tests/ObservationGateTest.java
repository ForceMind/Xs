package dev.forcemind.xs.tests;

import dev.forcemind.xs.core.ObservationGate;
import dev.forcemind.xs.core.ObservationRoutes;

public final class ObservationGateTest {
    private static int n=0;
    private static void eq(Object a,Object b) {
        if (!(a==null ? b==null : a.equals(b))) throw new AssertionError(a+" != "+b);
        n++;
    }
    private static ObservationGate.Message post(long epoch,long seq,String id) {
        return new ObservationGate.Message(ObservationGate.Type.POST_OPENED,
            epoch,seq,512,id,"https://x.com/user/status/"+id,
            "user","public content",null,0,0);
    }
    private static ObservationGate.Message feed(long epoch,long seq,double fraction,long dwell) {
        return new ObservationGate.Message(ObservationGate.Type.FEED_VISIBLE,
            epoch,seq,512,"123","https://x.com/user/status/123",
            "user","public content","home_following",fraction,dwell);
    }
    private static ObservationGate.NativeState state(long epoch,long clock,String route,
            String origin,boolean consent,boolean main,boolean foreground,boolean gesture) {
        return new ObservationGate.NativeState("native-local-scope",consent,
            foreground,main,gesture,origin,route,epoch,clock);
    }
    public static void main(String[] args) {
        eq(true,ObservationRoutes.trustedOrigin("https://x.com"));
        eq(true,ObservationRoutes.trustedOrigin("https://www.x.com:443"));
        for (String origin:new String[]{"https://x.com.evil.com","http://x.com",
                "https://twitter.com","https://user@x.com","https://x.com/login",
                "https://x.com:8888"}) eq(false,ObservationRoutes.trustedOrigin(origin));
        eq(ObservationRoutes.Kind.POST_DETAIL,
            ObservationRoutes.classify("https://x.com/user/status/123"));
        eq(ObservationRoutes.Kind.POST_DETAIL,
            ObservationRoutes.classify("https://x.com/i/web/status/123"));
        eq(ObservationRoutes.Kind.FEED,ObservationRoutes.classify("https://x.com/home"));
        for(String route:new String[]{"https://x.com/messages",
                "https://x.com/i/flow/login","https://x.com/settings/account",
                "https://x.com/compose/post","https://x.com/user",
                "https://twitter.com/user/status/123","https://x.com/%6dessages",
                "https://x.com.evil.com/user/status/123","http://x.com/home"})
            eq(ObservationRoutes.Kind.DENIED,ObservationRoutes.classify(route));

        eq("home_following",ObservationRoutes.feedSource("https://x.com/home?next=/search", "home_following"));
        eq("home_for_you",ObservationRoutes.feedSource("https://x.com/home/?q=/search", "home_for_you"));
        eq(null,ObservationRoutes.feedSource("https://x.com/home", null));
        eq(null,ObservationRoutes.feedSource("https://x.com/home", "search_results"));
        eq("search_results",ObservationRoutes.feedSource("https://x.com/search?q=/home", null));
        eq(null,ObservationRoutes.feedSource("https://x.com/explore", "home_for_you"));
        eq(null,ObservationRoutes.feedSource("https://x.com/messages?q=/search", "home_for_you"));

        ObservationGate gate=new ObservationGate();
        String detail="https://x.com/user/status/123",home="https://x.com/home";
        ObservationGate.NativeState normal=state(1,1000,detail,"https://x.com",
            true,true,true,true);
        eq(ObservationGate.Verdict.DISARMED,gate.admit(normal,post(1,1,"123")));
        gate.changeDocument(1);
        eq(ObservationGate.Verdict.ACCEPT,gate.admit(normal,post(1,1,"123")));
        eq(ObservationGate.Verdict.REPLAY,gate.admit(normal,post(1,1,"123")));
        eq(ObservationGate.Verdict.WRONG_POST,gate.admit(normal,post(1,2,"124")));
        eq(ObservationGate.Verdict.NOT_DELIBERATE,gate.admit(
            state(1,1001,detail,"https://x.com",true,true,true,false),post(1,2,"123")));
        eq(ObservationGate.Verdict.DISARMED,gate.admit(
            state(1,1001,detail,"https://x.com",false,true,true,true),post(1,2,"123")));
        eq(ObservationGate.Verdict.BAD_ORIGIN,gate.admit(
            state(1,1001,detail,"https://x.com",true,false,true,true),post(1,2,"123")));
        eq(ObservationGate.Verdict.BAD_ORIGIN,gate.admit(
            state(1,1001,detail,"https://evil.com",true,true,true,true),post(1,2,"123")));
        eq(ObservationGate.Verdict.BACKGROUND,gate.admit(
            state(1,1001,detail,"https://x.com",true,true,false,true),post(1,2,"123")));
        eq(ObservationGate.Verdict.ROUTE_DENIED,gate.admit(
            state(1,1001,"https://x.com/messages","https://x.com",true,true,true,true),
            post(1,2,"123")));
        eq(ObservationGate.Verdict.STALE,gate.admit(normal,post(2,2,"123")));
        ObservationGate.NativeState visible=state(1,1000,home,"https://x.com",
            true,true,true,false);
        eq(ObservationGate.Verdict.BAD_MESSAGE,gate.admit(visible,feed(1,2,0.59,1200)));
        eq(ObservationGate.Verdict.BAD_MESSAGE,gate.admit(visible,feed(1,2,0.6,1199)));
        for(int i=2;i<=40;i++)eq(ObservationGate.Verdict.ACCEPT,
            gate.admit(visible,feed(1,i,0.8,1300)));
        eq(ObservationGate.Verdict.THROTTLED,gate.admit(visible,feed(1,41,0.8,1300)));
        eq(ObservationGate.Verdict.ACCEPT,gate.admit(
            state(1,1100,home,"https://x.com",true,true,true,false),
            feed(1,41,0.8,1300)));
        gate.changeDocument(2);
        eq(ObservationGate.Verdict.STALE,gate.admit(normal,post(1,42,"123")));
        eq(ObservationGate.Verdict.ACCEPT,gate.admit(
            state(2,1200,detail,"https://x.com",true,true,true,true),post(2,1,"123")));
        gate.suspend();
        eq(ObservationGate.Verdict.DISARMED,gate.admit(
            state(2,1200,detail,"https://x.com",true,true,true,true),post(2,2,"123")));
        boolean reused=false;try{gate.changeDocument(2);}catch(IllegalArgumentException expected){reused=true;}
        eq(true,reused);
        gate.changeDocument(3);
        eq(ObservationGate.Verdict.STALE,gate.admit(
            state(2,1200,detail,"https://x.com",true,true,true,true),post(2,3,"123")));
        System.out.println("PASS "+n+" observation admission assertions (no WebView hook)");
    }
}
