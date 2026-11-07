package dev.forcemind.xs.android;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.forcemind.xs.core.ArchiveSearch;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real native dialogs and SYSTEM SAF picker, using only a generated test-APK document root.
 * No fake Activity results, real user documents, X sessions or remote document services.
 */
@RunWith(AndroidJUnit4.class)
public final class GovernanceUiTest {
    private static final String METADATA="xs-library-metadata.xsmeta",FULL="xs-library-encrypted.xsbackup";
    private static final String PASSWORD="fixture-only-password-42";
    private Instrumentation instrumentation;
    private Context app;
    private XsArchiveDatabase archive;
    private Activity screen;
    private String first,second,defaultLibrary;
    private final Set<String> owned=new LinkedHashSet<>();
    private boolean providerReady,testBodyCompleted;

    @Before public void setUp()throws Exception {
        instrumentation=InstrumentationRegistry.getInstrumentation();app=instrumentation.getTargetContext();
        AccessibilityServiceInfo info=instrumentation.getUiAutomation().getServiceInfo();
        info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;instrumentation.getUiAutomation().setServiceInfo(info);
        assertTrue(admin("xs_fixture_reset",null).getBoolean("ok"));providerReady=true;
        archive=new XsArchiveDatabase(app);defaultLibrary=XsLocalSpaces.defaultLibrary(app);
        queued(defaultLibrary,()->archive.ensureLibrary(defaultLibrary,"Xs local fixture library"));
        first="local:"+UUID.randomUUID();second="local:"+UUID.randomUUID();owned.add(first);owned.add(second);
        seed(first,"Governance fixture A","governanceneedle alpha","Fixture source note A");
        seed(second,"Governance fixture B","governanceneedle beta extended","Fixture source note B");
    }
    @After public void tearDown()throws Exception {
        Throwable cleanupFailure=null;
        try {
            // A failed picker assertion must not leave another application's foreground
            // Activity blocking the next fixture. Cancel through the real system Back action.
            for(int i=0;i<4&&documentsUiActive();i++) {
                instrumentation.getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                SystemClock.sleep(150);
            }
            if(screen!=null){instrumentation.runOnMainSync(screen::finish);instrumentation.waitForIdleSync();}
            for(String library:owned) {
                if(XsLibraryWrites.isDeleted(library))continue;
                queued(library,()->{XsMediaRepository.get(app).eraseLibrary(library);archive.clearAccount(library);
                    archive.getWritableDatabase().delete("libraries","storage_scope=?",new String[]{library});});
            }
        }catch(Throwable failure){cleanupFailure=failure;}
        finally {
            try{if(archive!=null)archive.close();}catch(Throwable failure){if(cleanupFailure==null)cleanupFailure=failure;}
            try{if(providerReady)assertTrue(admin("xs_fixture_reset",null).getBoolean("ok"));}catch(Throwable failure){if(cleanupFailure==null)cleanupFailure=failure;}
        }
        if(cleanupFailure!=null&&testBodyCompleted)throw new AssertionError("Synthetic fixture cleanup failed",cleanupFailure);
        // A failed setup/test already has the useful original exception; do not replace it with
        // an after-method reset failure. Every later setup retries cleanup before using the root.
    }

    @Test public void metadataSafExportPreviewCancelAndConfirmUseActualDocumentBytes()throws Exception {
        Set<String> before=libraries();openBackup();
        clickText("Export plain metadata","导出明文元数据");saveInFixtureRoot();
        awaitText("Metadata exported","元数据备份已导出");
        byte[] bytes=document(METADATA);assertTrue(bytes.length>0);
        XsLibraryBackup.ValidatedBackup actual=XsLibraryBackup.inspect(new ByteArrayInputStream(bytes));
        assertEquals("Governance fixture A",actual.label());assertEquals(Integer.valueOf(1),actual.counts().get("posts"));
        clickText("Choose plain metadata & preview","选择明文元数据并预览");openFixtureFile(METADATA);
        awaitText("Restore into a new library?","恢复到新的资料空间？");clickText("Cancel","取消");
        awaitText("Canceled; no data restored.","已取消；未恢复资料。");assertEquals(before,libraries());
        clickText("Choose plain metadata & preview","选择明文元数据并预览");openFixtureFile(METADATA);
        awaitText("Restore into a new library?","恢复到新的资料空间？");clickText("Create separate library","创建独立资料空间");
        String restored=awaitNewLibrary(before);assertEquals(defaultLibrary,XsLocalSpaces.current(app));
        assertSearchable(restored,"alpha");assertSearchable(first,"alpha");assertSearchable(second,"beta");
        assertEquals(0,XsMediaRepository.get(app).list(restored).size());
        testBodyCompleted=true;
    }

    @Test public void encryptedFullSafRoundTripValidatesPasswordAndRestoresActualImage()throws Exception {
        byte[] image=seedImage(first);Set<String> before=libraries();openBackup();
        clickText("Export encrypted full backup","导出加密完整备份");
        awaitText("Backup password","备份口令");setEditor(0,"short");setEditor(1,"short");clickText("OK","确定");
        assertNotNull(find(n->isEditor(n)));assertEquals(before,libraries());
        setEditor(0,PASSWORD);setEditor(1,PASSWORD);clickText("OK","确定");saveInFixtureRoot();
        awaitText("Encrypted full backup exported","加密完整备份已导出");
        byte[] encrypted=document(FULL);assertTrue(encrypted.length>56);assertEquals("XSENCBAK",new String(encrypted,0,8,StandardCharsets.US_ASCII));
        assertFalse(new String(encrypted,StandardCharsets.ISO_8859_1).contains("governanceneedle"));
        clickText("Restore encrypted full backup","恢复加密完整备份");awaitText("Backup password","备份口令");setEditor(0,PASSWORD);clickText("OK","确定");openFixtureFile(FULL);
        awaitText("Restore encrypted full backup?","恢复加密完整备份？");
        assertTrue(activeText().contains("1"));assertEquals(before,libraries());clickText("Create separate library","创建独立资料空间");
        String restored=awaitNewLibrary(before);assertEquals(defaultLibrary,XsLocalSpaces.current(app));assertSearchable(restored,"alpha");
        List<XsMediaRepository.CompletedAsset> files=XsMediaRepository.get(app).snapshotCompleted(restored);
        assertEquals(1,files.size());assertEquals("image/png",files.get(0).asset.mime);assertEquals("fixture-image",files.get(0).asset.category);
        assertArrayEquals(image,Files.readAllBytes(files.get(0).file.toPath()));assertEquals(1,XsMediaRepository.get(app).snapshotCompleted(first).size());
        testBodyCompleted=true;
    }

    @Test public void consolidationNativePreviewCancelConfirmAndUndoPreserveSourcesAndDefault()throws Exception {
        Set<String> before=libraries();
        screen=instrumentation.startActivitySync(XsLibrariesActivity.intent(app).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitText("Governance fixture A");clickText("Governance fixture A");clickText("Governance fixture B");setEditor(0,"Governance native merged fixture");
        clickText("Check & preview","检查并预览");awaitText("Create a separate library?","创建独立资料空间？");
        assertTrue(activeText().contains("Governance native merged fixture"));clickText("Cancel","取消");
        awaitText("Canceled; no library created.","已取消；未创建资料空间。");assertEquals(before,libraries());
        clickText("Check & preview","检查并预览");awaitText("Create a separate library?","创建独立资料空间？");clickText("Create library","确认创建");
        String result=awaitNewLibrary(before);assertEquals(defaultLibrary,XsLocalSpaces.current(app));
        // Equal-time conflicting bodies retain one current selection and both source versions.
        // Search the version index to verify preservation without inventing a later edit time.
        assertSearchableVersion(result,"alpha");assertSearchableVersion(result,"beta");
        awaitText("Open the new library?","打开新资料空间？");clickText("Undo new library","撤销新空间");
        awaitText("Undo the newly created library?","撤销刚创建的新空间？");clickText("Undo new library","确认撤销");
        awaitText("New library undone; sources and default preserved.","新空间已撤销，来源和默认库保留。");
        await(()->libraries().equals(before),"Only the newly created library is removed");
        assertEquals(defaultLibrary,XsLocalSpaces.current(app));assertSearchable(first,"alpha");assertSearchable(second,"beta");
        try(Cursor c=archive.search(result,new ArchiveSearch.Filter())){assertEquals(0,c.getCount());}
        testBodyCompleted=true;
    }

    @Test public void assetsMultiSelectSafZipContainsEverySelectedOriginalFile()throws Exception {
        seedImages(first,2);Map<String,byte[]> expected=new LinkedHashMap<>();
        for(XsMediaRepository.CompletedAsset file:XsMediaRepository.get(app).snapshotCompleted(first))
            expected.put(file.asset.filename,Files.readAllBytes(file.file.toPath()));
        assertEquals(2,expected.size());
        screen=instrumentation.startActivitySync(XsAssetsActivity.intent(app,first).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitText("2 matching resources","2 个匹配资源");clickId(XsAssetsActivity.ID_ACTIONS);
        clickText("Select loaded resources","选择已加载资源");awaitText("Selected 2","所选 2");
        clickId(XsAssetsActivity.ID_ACTIONS);clickText("Export selected files (multiple as ZIP)","导出所选文件（多选 ZIP）");saveInFixtureRoot();
        AtomicReference<byte[]> complete=new AtomicReference<>();
        await(()->{try {
            byte[] bytes=Base64.decode(admin("xs_fixture_read","Xs-selected-media.zip").getString("data",""),Base64.NO_WRAP);
            int n=bytes.length;if(n<22||bytes[n-22]!=0x50||bytes[n-21]!=0x4b||bytes[n-20]!=0x05||bytes[n-19]!=0x06)return false;
            complete.set(bytes);return true;
        }catch(Exception failure){throw new AssertionError(failure);}},"The native ZIP export finished its central directory");
        Set<String> actual=new LinkedHashSet<>();
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(complete.get()))) {
            ZipEntry entry;byte[] buffer=new byte[1024];while((entry=zip.getNextEntry())!=null) {
                assertFalse(entry.isDirectory());assertTrue(expected.containsKey(entry.getName()));assertTrue(actual.add(entry.getName()));
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();int n;while((n=zip.read(buffer))!=-1){assertTrue(bytes.size()+n<4096);bytes.write(buffer,0,n);}
                assertArrayEquals(expected.get(entry.getName()),bytes.toByteArray());zip.closeEntry();
            }
        }
        assertEquals(expected.keySet(),actual);assertEquals(2,XsMediaRepository.get(app).snapshotCompleted(first).size());
        assertEquals(defaultLibrary,XsLocalSpaces.current(app));
        testBodyCompleted=true;
    }

    private void seed(String library,String label,String body,String note)throws Exception {
        queued(library,()->{archive.ensureLibrary(library,label);archive.recordObservedOpen(library,"https://x.com/fixture/status/8801","fixture",body,null,1000L,"governance-open");
            archive.favorite(library,"https://x.com/fixture/status/8801","fixture",body,null,1001L);archive.putFavoriteDetails(library,"8801",note,"fixture,governance");});
    }
    private byte[] seedImage(String library)throws Exception {return seedImages(library,1).get(0);}
    private List<byte[]> seedImages(String library,int count)throws Exception {
        List<byte[]> originals=new ArrayList<>();List<File> stages=new ArrayList<>();List<XsMediaRepository.RestoredAsset> assets=new ArrayList<>();
        try {
            for(int i=0;i<count;i++) {
                byte[] bytes;Bitmap bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);bitmap.setPixel(0,0,0xff123456+i);
                try(ByteArrayOutputStream out=new ByteArrayOutputStream()){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));bytes=out.toByteArray();}finally{bitmap.recycle();}
                originals.add(bytes);File stage=new File(app.getCacheDir(),"governance-image-"+UUID.randomUUID()+".png");stages.add(stage);
                try(FileOutputStream out=new FileOutputStream(stage)){out.write(bytes);out.getFD().sync();}
                String hash=hex(MessageDigest.getInstance("SHA-256").digest(bytes));
                assets.add(new XsMediaRepository.RestoredAsset("https://x.com/fixture/status/8801","IMAGE","image/png",bytes.length,hash,"fixture-image",1002L+i,0,0,0,0,0,0,stage));
            }
            queued(library,()->{try{XsMediaRepository.get(app).restoreCompleted(library,assets);}catch(Exception failure){throw new AssertionError(failure);}});
        }finally{for(File stage:stages)stage.delete();}return originals;
    }
    private void openBackup(){screen=instrumentation.startActivitySync(XsBackupActivity.intent(app,first).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));awaitText("Xs library backup & restore","Xs 资料备份与恢复");}
    private Set<String> libraries(){Set<String> result=new LinkedHashSet<>();try(Cursor c=archive.libraries()){while(c.moveToNext())result.add(c.getString(3));}return result;}
    private String awaitNewLibrary(Set<String> before)throws Exception {
        await(()->libraries().size()==before.size()+1,"A concrete new library was committed");Set<String> added=libraries();added.removeAll(before);assertEquals(1,added.size());String id=added.iterator().next();owned.add(id);return id;
    }
    private void assertSearchable(String library,String keyword){ArchiveSearch.Filter filter=new ArchiveSearch.Filter();filter.keyword=keyword;filter.favoriteOnly=true;try(Cursor c=archive.search(library,filter)){assertEquals(1,c.getCount());}}
    private void assertSearchableVersion(String library,String keyword){ArchiveSearch.Filter filter=new ArchiveSearch.Filter();filter.keyword=keyword;filter.favoriteOnly=true;filter.includeSnapshotVersions=true;try(Cursor c=archive.search(library,filter)){assertEquals(1,c.getCount());}}
    private void queued(String library,Runnable operation)throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> problem=new AtomicReference<>();
        assertTrue(XsLibraryWrites.submit(library,()->{try{operation.run();}catch(Throwable failure){problem.set(failure);}finally{done.countDown();}}));
        assertTrue("Fixture writer completed",done.await(15,TimeUnit.SECONDS));if(problem.get()!=null)throw new AssertionError(problem.get());
    }
    private byte[] document(String name)throws Exception {
        AtomicReference<byte[]> bytes=new AtomicReference<>();await(()->{try{Bundle result=admin("xs_fixture_read",name);String encoded=result.getString("data","");if(encoded.isEmpty())return false;bytes.set(Base64.decode(encoded,Base64.NO_WRAP));return true;}catch(Exception failure){throw new AssertionError(failure);}},"The selected document contains actual exported bytes");return bytes.get();
    }
    private Bundle admin(String method,String arg)throws Exception {
        Bundle result=app.getContentResolver().call(Uri.parse("content://"+GovernanceFixtureAdminProvider.AUTHORITY),method,arg,null);
        assertNotNull("Signature-gated synthetic fixture provider is visible",result);return result;
    }

    private void fixtureRoot() {
        await(()->find(n->n.getPackageName()!=null&&n.getPackageName().toString().contains("documentsui"))!=null,"System DocumentsUI is active");
        AccessibilityNodeInfo drawer=find(n->n.getClassName()!=null&&n.getClassName().toString().contains("ImageButton")&&n.getContentDescription()!=null&&
            (n.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT).contains("roots")||n.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT).contains("navigation")||n.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT).contains("drawer")));
        if(drawer!=null){assertTrue("Open SAF locations",drawer.performAction(AccessibilityNodeInfo.ACTION_CLICK));SystemClock.sleep(100);}
        AccessibilityNodeInfo root=find(n->text(n).equals(GovernanceDocumentsProvider.TITLE)&&n.isVisibleToUser());
        if(root==null) {
            AccessibilityNodeInfo navigation=find(n->n.getClassName()!=null&&n.getClassName().toString().contains("ImageButton")&&n.isVisibleToUser());
            if(navigation!=null)navigation.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        clickText(GovernanceDocumentsProvider.TITLE);
    }
    private boolean documentsUiActive(){return find(n->n.getPackageName()!=null&&n.getPackageName().toString().contains("documentsui"))!=null;}
    private void saveInFixtureRoot(){fixtureRoot();clickText("Save","保存");await(()->!documentsUiActive(),"SAF returned the saved document");}
    private void openFixtureFile(String name){fixtureRoot();clickText(name);await(()->!documentsUiActive(),"SAF returned the selected document");}
    private void setEditor(int index,String value) {
        List<AccessibilityNodeInfo> fields=new ArrayList<>();AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();collect(root,n->{if(isEditor(n)&&n.isVisibleToUser())fields.add(n);return false;},new int[]{0});
        assertTrue("Native editor "+index+" is visible",fields.size()>index);Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
        assertTrue("Native editor accepts input",fields.get(index).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args));
    }
    private static boolean isEditor(AccessibilityNodeInfo n){return n.getClassName()!=null&&n.getClassName().toString().contains("EditText");}
    private void clickText(String... alternatives) {
        AccessibilityNodeInfo node=awaitNode(n->{if(!n.isVisibleToUser()||!n.isEnabled())return false;String text=text(n);for(String option:alternatives)if(text.equalsIgnoreCase(option))return true;return false;});
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        AccessibilityNodeInfo original=node;
        for(int i=0;i<5&&node!=null;i++,node=node.getParent())if(node.isClickable()){assertTrue("Click native UI "+Arrays.toString(alternatives),node.performAction(AccessibilityNodeInfo.ACTION_CLICK));return;}
        // DocumentsUI's RecyclerView can expose non-clickable title/row nodes while its
        // touch listener owns selection. Tap only its freshly observed visible node bounds.
        if(original.getPackageName()!=null&&original.getPackageName().toString().contains("documentsui")) {
            Rect bounds=new Rect();original.getBoundsInScreen(bounds);assertFalse("Visible document has screen bounds",bounds.isEmpty());
            long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0);
            MotionEvent up=MotionEvent.obtain(now,now+16,MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0);
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try{assertTrue("Touch system document row",instrumentation.getUiAutomation().injectInputEvent(down,true));assertTrue("Release system document row",instrumentation.getUiAutomation().injectInputEvent(up,true));}finally{down.recycle();up.recycle();}return;
        }
        throw new AssertionError("Native UI is not clickable: "+Arrays.toString(alternatives)+"\n"+activeText());
    }
    private void clickId(int id){instrumentation.runOnMainSync(()->{android.view.View button=screen.findViewById(id);assertNotNull(button);assertTrue(button.performClick());});}
    private void awaitText(String... alternatives){awaitNode(n->{String text=text(n);for(String option:alternatives)if(text.contains(option))return true;return false;});}
    private AccessibilityNodeInfo awaitNode(Predicate<AccessibilityNodeInfo> predicate){AtomicReference<AccessibilityNodeInfo> result=new AtomicReference<>();await(()->{AccessibilityNodeInfo found=find(predicate);if(found==null)return false;result.set(found);return true;},"Native UI node appeared");return result.get();}
    private AccessibilityNodeInfo find(Predicate<AccessibilityNodeInfo> predicate){return collect(instrumentation.getUiAutomation().getRootInActiveWindow(),predicate,new int[]{0});}
    private AccessibilityNodeInfo collect(AccessibilityNodeInfo node,Predicate<AccessibilityNodeInfo> predicate,int[] visited){
        if(node==null||++visited[0]>1500)return null;if(predicate.test(node))return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=collect(node.getChild(i),predicate,visited);if(found!=null)return found;}return null;
    }
    private static String text(AccessibilityNodeInfo node){if(node.isPassword())return "[password field]";CharSequence value=node.getText();return value==null?"":value.toString();}
    private String activeText(){StringBuilder out=new StringBuilder();collect(instrumentation.getUiAutomation().getRootInActiveWindow(),n->{out.append(text(n)).append('\n');return false;},new int[]{0});return out.toString();}
    private void await(BooleanSupplier condition,String explanation){long end=SystemClock.elapsedRealtime()+60_000;while(SystemClock.elapsedRealtime()<end){if(condition.getAsBoolean())return;SystemClock.sleep(50);}throw new AssertionError(explanation+"\n"+activeText());}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte value:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}
}
