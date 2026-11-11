package dev.forcemind.xs.tests;

import dev.forcemind.xs.core.CaptureSession;
import dev.forcemind.xs.core.SerialWriteCoordinator;
import dev.forcemind.xs.core.StrictJson;
import dev.forcemind.xs.core.SnapshotPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class CaptureSessionTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("Capture assertion "+checks);}
    private static final class Sink implements CaptureSession.Sink {
        final List<CaptureSession.Event> opens=new ArrayList<>(),feeds=new ArrayList<>(),enriched=new ArrayList<>();
        public void opened(CaptureSession.Event e){opens.add(e);}public void feed(CaptureSession.Event e){feeds.add(e);}
        public void enriched(CaptureSession.Event e){enriched.add(e);}
    }
    private static CaptureSession.Card card(String id,String run,double fraction){return new CaptureSession.Card("https://x.com/alice/status/"+id,"alice","离线正文",null,fraction,run);}
    public static void main(String[]args)throws Exception {
        Sink sink=new Sink();CaptureSession s=new CaptureSession("library:test",sink);
        s.setForeground(true);s.navigation("https://x.com/alice/status/123","native1",true,1000);
        check(sink.opens.isEmpty());s.setConsent(true,true,3000);
        s.navigation("https://x.com/alice/status/123","native1",true,1001);
        check(sink.opens.size()==1);s.detail(card("123","1",1),1002);
        s.detail(card("123","1",1),1003);s.navigation("https://x.com/alice/status/123","native1",true,1004);
        check(sink.opens.size()==1&&sink.enriched.size()==1);
        CaptureSession.Event fastOpen=sink.opens.get(0),oldBody=sink.enriched.get(0);
        s.navigation("https://x.com/home","native2",false,1005);
        check(s.isCurrent(fastOpen)&&!s.isCurrent(oldBody));
        s.navigation("https://x.com/alice/status/123","native3",true,1006);check(sink.opens.size()==2);
        s.navigation("https://x.com/home","native4",false,1007);
        for(int i=0;i<=14;i++)s.sample(Arrays.asList(card("456","a",.6)),"home_for_you",i*250,2000+i*250);
        check(!sink.feeds.isEmpty());check(sink.feeds.get(0).durationMs==3000);
        check(sink.feeds.get(sink.feeds.size()-1).durationMs==3000); // bounded write cadence
        String exposure=sink.feeds.get(0).exposureId;
        for(CaptureSession.Event e:sink.feeds)check(exposure.equals(e.exposureId));
        // True reentry is a new event; it is never suppressed for two minutes.
        s.sample(new ArrayList<>(),"home_for_you",3750,5750);
        check(sink.feeds.get(sink.feeds.size()-1).durationMs==3500); // flush confirmed tail
        check(sink.feeds.get(sink.feeds.size()-1).capturedAtMs==5500);
        for(int i=0;i<=12;i++)s.sample(Arrays.asList(card("456","b",1)),"home_for_you",4000+i*250,6000+i*250);
        check(!exposure.equals(sink.feeds.get(sink.feeds.size()-1).exposureId));
        int before=sink.feeds.size();s.setOverlay(true);s.setOverlay(false);
        for(int i=0;i<=4;i++)s.sample(Arrays.asList(card("789","c",1)),"home_for_you",8000+i*250,10000+i*250);
        check(before==sink.feeds.size());
        // A sampling gap/background cannot be stitched into a continuous exposure.
        s.sample(Arrays.asList(card("789","c",1)),"home_for_you",14000,16000);
        check(before==sink.feeds.size());s.setForeground(false);s.setForeground(true);
        s.sample(Arrays.asList(card("789","c",1)),"home_for_you",17000,19000);check(before==sink.feeds.size());
        // 59% and a malicious source are ineligible. Setting changes only future runs.
        s.sample(Arrays.asList(card("789","c",.59)),"home_for_you",17250,19250);
        s.sample(Arrays.asList(card("789","c",1)),"login",17500,19500);check(before==sink.feeds.size());
        s.setConsent(true,true,1200);
        for(int i=0;i<6;i++)s.sample(Arrays.asList(card("900","d",1)),"home_for_you",18000+i*250,20000+i*250);
        check(sink.feeds.get(sink.feeds.size()-1).durationMs==1250);
        s.navigation("https://x.com/messages","native5",false,22000);
        s.sample(Arrays.asList(card("999","e",1)),"home_for_you",22000,22000);check(sink.opens.size()==2);
        s.destroy();check(!s.isCurrent(fastOpen));
        for(String bad:new String[]{"{\"v\":1,\"v\":2}","{}{}","{\"x\":1,}","[1,]","01","{\"x\":NaN}","{\"x\":1e999}"}){
            boolean failed=false;try{StrictJson.parse(bad);}catch(IllegalArgumentException ok){failed=true;}check(failed);
        }
        check(StrictJson.parse("{\"a\":[1,true,null,\"文\\n本\"]}") instanceof java.util.Map);
        SerialWriteCoordinator writer=new SerialWriteCoordinator();AtomicInteger saved=new AtomicInteger();
        long old=writer.token();CountDownLatch done=new CountDownLatch(1);
        writer.barrier(()->saved.set(0));check(!writer.submit(old,()->saved.set(99)));
        writer.submit(writer.token(),()->{saved.incrementAndGet();done.countDown();});
        check(done.await(3,TimeUnit.SECONDS));check(saved.get()==1);writer.close();
        // A full queue cannot prevent erasure, and skipped media callbacks release their parts.
        SerialWriteCoordinator raced=new SerialWriteCoordinator();
        CountDownLatch running=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);
        AtomicInteger discarded=new AtomicInteger(),value=new AtomicInteger();long stale=raced.token();
        raced.submit(stale,()->{running.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException x){throw new RuntimeException(x);}value.set(1);});
        check(running.await(3,TimeUnit.SECONDS));
        for(int i=0;i<128;i++)check(raced.submit(stale,()->value.set(99),discarded::incrementAndGet));
        check(!raced.submit(stale,()->value.set(100)));
        check(raced.barrier(()->value.set(0))); // must return while previous operation is blocked
        check(raced.barrier(()->value.incrementAndGet())); // previous mandatory erasure cannot vanish
        check(raced.submit(raced.token(),()->{value.incrementAndGet();finished.countDown();}));
        release.countDown();check(finished.await(5,TimeUnit.SECONDS));
        check(value.get()==2);check(discarded.get()==128);raced.close();
        stressBoundariesAndThroughput(Arrays.asList(args).contains("--long-stress"));
        System.out.println("CaptureSession: "+checks+" assertions passed");
    }
    private static void stressBoundariesAndThroughput(boolean fullDay) {
        Sink sink=new Sink();CaptureSession s=new CaptureSession("library:epochs",sink);
        s.setConsent(true,true,1200);s.setForeground(true);
        s.navigation("https://x.com/alice/status/41","native-epoch",true,100);
        CaptureSession.Event open=sink.opens.get(0);
        s.detail(new CaptureSession.Card("https://x.com/alice/status/41","alice","complete body",null,1,"detail",true),100);
        check(sink.enriched.get(0).snapshotQuality==SnapshotPolicy.Quality.COMPLETE);
        check(sink.enriched.get(0).capturedAtMs==100);
        s.navigation("https://x.com/home","native-feed",false,101);
        for(int i=0;i<=6;i++)s.sample(Arrays.asList(card("42","stable",1)),"home_for_you",i*200,1000+i*200);
        CaptureSession.Event feed=sink.feeds.get(0);check(feed.snapshotQuality==SnapshotPolicy.Quality.PARTIAL);
        check(s.isCurrent(open)&&s.isCurrent(feed));
        CaptureSession replacement=new CaptureSession("library:epochs",sink);
        replacement.setConsent(true,true,1200);replacement.setForeground(true);
        check(!replacement.isCurrent(open)&&!replacement.isCurrent(feed));replacement.destroy();
        s.setConsent(false,false,1200);check(!s.isCurrent(open)&&!s.isCurrent(feed));
        s.setConsent(true,true,1200);check(!s.isCurrent(open)&&!s.isCurrent(feed));
        // A destroyed state cannot be revived by queued resume/options/navigation calls.
        int oldOpens=sink.opens.size(),oldFeeds=sink.feeds.size();s.destroy();
        s.setConsent(true,true,1200);s.setForeground(true);s.setOverlay(false);
        s.navigation("https://x.com/alice/status/43","late",true,9999);
        s.sample(Arrays.asList(card("42","stable",1)),"home_for_you",1400,2400);
        check(sink.opens.size()==oldOpens&&sink.feeds.size()==oldFeeds);

        AtomicInteger feeds=new AtomicInteger(),opens=new AtomicInteger();
        AtomicInteger capped=new AtomicInteger();
        CaptureSession bounded=new CaptureSession("library:stress",new CaptureSession.Sink(){
            public void opened(CaptureSession.Event e){opens.incrementAndGet();}
            public void enriched(CaptureSession.Event e){throw new AssertionError("feed is not detail");}
            public void feed(CaptureSession.Event e){feeds.incrementAndGet();if(e.durationMs==CaptureSession.MAX_DWELL_MS){
                check("CAPPED_LOWER_BOUND".equals(e.durationQuality));capped.incrementAndGet();}}
        });
        bounded.setForeground(true);bounded.setConsent(true,true,1200);
        bounded.navigation("https://x.com/home","native-stress",false,100);
        List<CaptureSession.Card> viewport=new ArrayList<>();
        for(int i=0;i<CaptureSession.MAX_CARDS;i++)viewport.add(card(String.valueOf(100+i),"same-run",1));
        // Default covers the cap plus one minute; --long-stress simulates a full day.
        for(int i=0;i<=(fullDay?432000:3300);i++)bounded.sample(viewport,"home_for_you",i*200L,1000+i*200L);
        check(capped.get()==CaptureSession.MAX_CARDS);
        check(feeds.get()<=CaptureSession.MAX_CARDS*(CaptureSession.MAX_DWELL_MS/CaptureSession.UPDATE_INTERVAL_MS+2));
        int completed=feeds.get();
        bounded.sample(new ArrayList<>(),"home_for_you",86_400_200,86_401_200);
        check(feeds.get()==completed&&opens.get()==0);
        // Twenty thousand different post identities over a four-slot virtual list must not
        // retain prior posts, coalesce genuine entries, or manufacture native open events.
        bounded.setConsent(true,true,1200);long clock=86_401_000;
        for(int batch=0;batch<5000;batch++) {
            viewport=new ArrayList<>();for(int slot=0;slot<4;slot++)viewport.add(card(String.valueOf(10000+batch*4+slot),"rebound:"+batch,1));
            for(int sample=0;sample<=6;sample++)bounded.sample(viewport,"home_for_you",clock+sample*200,clock+sample*200);
            bounded.sample(new ArrayList<>(),"home_for_you",clock+1400,clock+1400);clock+=1600;
        }
        check(feeds.get()==completed+20000&&opens.get()==0);
        // Duplicate card IDs, invalid runs, clock regression and >600ms sampling gaps never
        // combine disjoint intervals into a qualifying event.
        viewport=new ArrayList<>();for(int i=0;i<24;i++)viewport.add(card("999999","duplicate",1));
        int before=feeds.get();
        for(int i=0;i<=6;i++)bounded.sample(viewport,"home_for_you",clock+i*200,clock+i*200);
        check(feeds.get()==before+1);clock+=2000;
        for(int i=0;i<1000;i++) {
            bounded.setOverlay(true);bounded.setOverlay(false);
            bounded.sample(viewport,"home_for_you",clock,clock);
            bounded.sample(viewport,"home_for_you",clock+600,clock+600);
            bounded.setForeground(false);bounded.setForeground(true);
            bounded.sample(viewport,"home_for_you",clock+800,clock+800);
            bounded.sample(viewport,"home_for_you",clock+1600,clock+1600); // gap resets
            bounded.sample(viewport,"home_for_you",clock+1500,clock+1500); // clock regresses
            bounded.navigation("https://x.com/messages","private:"+i,false,clock+1700);
            bounded.navigation("https://x.com/home","feed:"+i,false,clock+1800);clock+=2000;
        }
        check(feeds.get()==before+1);bounded.destroy();
        System.out.println("Capture stress: 20,000 virtual posts; "+(fullDay?"24h":"11min")+" x 24 cards at 200ms; "+completed+" writes including 24 final caps; 1,000 lifecycle cycles");
    }

}
