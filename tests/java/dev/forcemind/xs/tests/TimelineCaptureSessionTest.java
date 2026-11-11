package dev.forcemind.xs.tests;

import dev.forcemind.xs.core.CaptureSession;
import dev.forcemind.xs.core.SnapshotPolicy;
import dev.forcemind.xs.core.TimelineCaptureSession;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real admission state transitions, independent from storage fixtures and dwell. */
public final class TimelineCaptureSessionTest {
    private static int assertions;
    private static void eq(Object expected,Object actual){assertions++;if(expected==null?actual!=null:!expected.equals(actual))throw new AssertionError(expected+" != "+actual);}
    private static CaptureSession.Card card(String id,String body,double fraction){return new CaptureSession.Card("https://x.com/alice/status/"+id,"alice",body,null,fraction,"run",true);}
    public static void main(String[] args){
        List<TimelineCaptureSession.Event> accepted=new ArrayList<>();
        TimelineCaptureSession session=new TimelineCaptureSession("library:fixture");session.setSink(accepted::add);
        session.setForeground(true);session.navigation("https://x.com/home","document-1");
        session.sample(Arrays.asList(card("1","quick",.1)),1000);eq(0,accepted.size());
        session.setConsent(true);session.sample(Arrays.asList(card("1","quick",.1)),1001);eq(1,accepted.size());
        TimelineCaptureSession.Event first=accepted.get(0);
        eq("unknown",first.source);eq("native_session",first.batchReason);eq("offline_dom_fixture",first.provenance);
        eq("https://x.com/i/web/status/1",first.url);eq(SnapshotPolicy.Quality.PARTIAL,first.quality);eq(1L,first.sequence);
        session.sample(Arrays.asList(card("1","quick",.1)),1500);eq(1,accepted.size());
        session.sample(Arrays.asList(card("1","edited",.1)),1600);eq(2,accepted.size());eq(first.sequence,accepted.get(1).sequence);
        session.sample(Arrays.asList(card("2","",.01),card("2","",.01)),1700);eq(3,accepted.size());eq(SnapshotPolicy.Quality.LINK_ONLY,accepted.get(2).quality);eq(2L,accepted.get(2).sequence);
        session.navigation("https://x.com/messages","sensitive");session.sample(Arrays.asList(card("3","private",1)),1800);eq(3,accepted.size());
        eq(true,session.isCurrent(first)); // accepted immutable work may finish after quick return
        session.navigation("https://x.com/home?next=/search","document-2");session.setFeedContext("home_following");
        session.sample(Arrays.asList(card("1","quick",.9)),2000);eq(4,accepted.size());
        eq("home_following",accepted.get(3).source);eq("native_stream_switch",accepted.get(3).batchReason);eq(false,first.batchId.equals(accepted.get(3).batchId));
        session.sample(Arrays.asList(card("1","quick",.9)),2001,3602000);eq("native_session",accepted.get(4).batchReason);
        accepted.remove(4);
        session.refreshed();session.sample(Arrays.asList(card("1","quick",1)),2100);eq("native_refresh",accepted.get(4).batchReason);
        session.suspectedRefresh();session.sample(Arrays.asList(card("1","quick",1)),2200);eq("suspected_refresh",accepted.get(5).batchReason);
        session.setOverlay(true);session.sample(Arrays.asList(card("4","covered",1)),2300);eq(6,accepted.size());session.setOverlay(false);
        session.setForeground(false);session.sample(Arrays.asList(card("4","background",1)),2400);eq(6,accepted.size());session.setForeground(true);
        session.setConsent(false);eq(false,session.isCurrent(first));session.setConsent(true);eq(false,session.isCurrent(first));
        session.navigation("https://x.com/explore","explore");session.sample(Arrays.asList(card("5","explore",1)),2500);eq("unknown",accepted.get(6).source);
        session.navigation("https://x.com/search?q=/home","search");session.sample(Arrays.asList(card("6","search",1)),2600);eq("search_results",accepted.get(7).source);
        TimelineCaptureSession.Event preClear=accepted.get(7);session.invalidate();eq(false,session.isCurrent(preClear));
        session.sample(Arrays.asList(card("6","search",1)),2700);eq(9,accepted.size());
        for(CaptureSession.Card invalid:Arrays.asList(card("7","zero",0),card("7","nan",Double.NaN),card("7","over",1.1),card("7",new String(new char[2049]),1)))
            session.sample(Collections.singletonList(invalid),2800);
        eq(9,accepted.size());
        TimelineCaptureSession other=new TimelineCaptureSession("library:fixture");other.setConsent(true);eq(false,other.isCurrent(accepted.get(8)));
        session.navigation("https://x.com/home","cap");
        int before=accepted.size();
        for(int i=0;i<TimelineCaptureSession.MAX_POSTS_PER_BATCH+5;i++)session.sample(Collections.singletonList(card(Integer.toString(10000+i),"bounded",.2)),3000+i);
        eq(before+TimelineCaptureSession.MAX_POSTS_PER_BATCH,accepted.size());eq(5L,session.dropped());
        TimelineCaptureSession.Event preDestroy=accepted.get(accepted.size()-1);session.destroy();eq(false,session.isCurrent(preDestroy));
        session.setConsent(true);session.sample(Collections.singletonList(card("900000","late",1)),5000);eq(before+TimelineCaptureSession.MAX_POSTS_PER_BATCH,accepted.size());
        lockOrderRegression();
        System.out.println("PASS "+assertions+" independent timeline admission assertions");
    }
    /** Deterministic session->store admission versus store->validator persistence ordering. */
    private static void lockOrderRegression(){
        Object store=new Object();AtomicBoolean blockSink=new AtomicBoolean();
        AtomicReference<TimelineCaptureSession.Event> first=new AtomicReference<>();
        AtomicReference<Throwable> failure=new AtomicReference<>();
        CountDownLatch storeHeld=new CountDownLatch(1),insideSink=new CountDownLatch(1),finished=new CountDownLatch(2);
        TimelineCaptureSession session=new TimelineCaptureSession("library:lock-order");
        session.setSink(event->{
            if(!blockSink.get()){first.set(event);return;}
            insideSink.countDown();
            synchronized(store){/* Match concrete store.ticket() while capture's sample monitor is held. */}
        });
        session.setForeground(true);session.setConsent(true);session.navigation("https://x.com/home","lock-document");
        session.sample(Collections.singletonList(card("42","first",1)),1000);
        Thread writer=new Thread(()->{
            try{synchronized(store){
                storeHeld.countDown();
                if(!insideSink.await(3,TimeUnit.SECONDS))throw new AssertionError("Sample never entered the sink");
                if(!session.isCurrent(first.get()))throw new AssertionError("Valid admission was rejected");
            }}catch(Throwable problem){failure.compareAndSet(null,problem);}finally{finished.countDown();}
        },"timeline-store-validator");
        Thread sampler=new Thread(()->{
            try{
                if(!storeHeld.await(3,TimeUnit.SECONDS))throw new AssertionError("Writer never acquired store monitor");
                blockSink.set(true);session.sample(Collections.singletonList(card("42","updated",1)),1001);
            }catch(Throwable problem){failure.compareAndSet(null,problem);}finally{finished.countDown();}
        },"timeline-native-sample");
        // A failing old-lock regression must terminate the JVM rather than retain deadlocked threads.
        writer.setDaemon(true);sampler.setDaemon(true);writer.start();sampler.start();
        try{eq(true,finished.await(5,TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
        eq(null,failure.get());
        session.setConsent(false);session.setConsent(true);eq(false,session.isCurrent(first.get()));
    }

}
