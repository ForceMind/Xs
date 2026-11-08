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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real native dialogs and SYSTEM SAF picker, using only a generated test-APK document root.
 * No fake Activity results, real user documents, X sessions or remote document services.
 */
@RunWith(AndroidJUnit4.class)
public final class GovernanceUiTest {
    @Rule public final TestName testName=new TestName();
    private static final String METADATA="xs-library-metadata.xsmeta",FULL="xs-library-encrypted.xsbackup";
    private static final String PASSWORD="fixture-only-password-42";
    private Instrumentation instrumentation;
    private Context app;
    private XsArchiveDatabase archive;
    private Activity screen;
    private String first,second,defaultLibrary;
    private final Set<String> owned=new LinkedHashSet<>();
    private boolean providerReady,testBodyCompleted;
    private BackupRecreation pendingRecreation;

    @Before public void setUp()throws Exception {
        instrumentation=InstrumentationRegistry.getInstrumentation();app=instrumentation.getTargetContext();
        AccessibilityServiceInfo info=instrumentation.getUiAutomation().getServiceInfo();
        info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;instrumentation.getUiAutomation().setServiceInfo(info);
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
            if(providerReady&&!testBodyCompleted)saveFailureArtifacts();
            // A failed picker assertion must not leave another application's foreground
            // Activity blocking the next fixture. Cancel through the real system Back action.
            for(int i=0;i<4&&documentsUiActive();i++) {
                instrumentation.getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                SystemClock.sleep(150);
            }
            if(pendingRecreation!=null)awaitBackupRecreation();
            if(screen!=null){instrumentation.runOnMainSync(screen::finish);instrumentation.waitForIdleSync();}
            for(String library:owned) {
                if(XsLibraryWrites.isDeleted(library))continue;
                queued(library,()->{XsMediaRepository.get(app).eraseLibrary(library);archive.clearAccount(library);
                    archive.getWritableDatabase().delete("libraries","storage_scope=?",new String[]{library});});
            }
        }catch(Throwable failure){cleanupFailure=failure;}
        finally {
            if(pendingRecreation!=null){pendingRecreation.close();pendingRecreation=null;}
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

    @Test public void pendingMetadataSafRequestsSurviveRecreationAndCancellation()throws Exception {
        Set<String> before=libraries();openBackup();
        clickText("Export plain metadata","导出明文元数据");recreateBackupBehindPicker();saveInFixtureRoot();
        awaitNativeBackupStatus("Metadata exported","元数据备份已导出");
        assertEquals("Governance fixture A",XsLibraryBackup.inspect(new ByteArrayInputStream(document(METADATA))).label());
        clickText("Choose plain metadata & preview","选择明文元数据并预览");recreateBackupBehindPicker();
        assertTrue(instrumentation.getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK));
        await(()->!documentsUiActive(),"The actual metadata picker was canceled");awaitBackupRecreation();assertEquals(before,libraries());
        // A canceled retained request must not consume the next real selection.
        clickText("Choose plain metadata & preview","选择明文元数据并预览");recreateBackupBehindPicker();openFixtureFile(METADATA);
        awaitText("Restore into a new library?","恢复到新的资料空间？");
        awaitNativeBackupStatus("File validated; waiting for your choice.","文件已验证，等待你的选择。");
        clickText("Create separate library","创建独立资料空间");
        String restored=awaitNewLibrary(before);awaitNativeBackupStatus("Restored separately; your default library is unchanged.","已恢复到独立资料空间，默认库保持原样。");
        assertSearchable(restored,"alpha");assertSearchable(first,"alpha");
        assertEquals(defaultLibrary,XsLocalSpaces.current(app));testBodyCompleted=true;
    }

    @Test public void pendingStaleMetadataPickerCannotWriteAfterLibraryBarrier()throws Exception {
        Set<String> before=libraries();openBackup();
        clickText("Export plain metadata","导出明文元数据");recreateBackupBehindPicker();
        XsLibraryWrites.invalidate(first);saveInFixtureRoot();
        instrumentation.waitForIdleSync();
        assertEquals("An invalidated picker must not write metadata",0L,admin("xs_fixture_read",METADATA).getLong("bytes"));
        assertEquals(before,libraries());assertSearchable(first,"alpha");assertSearchable(second,"beta");
        testBodyCompleted=true;
    }

    @Test public void pendingEncryptedSafRequestsRetainPasswordOnlyInMemoryAcrossRecreation()throws Exception {
        byte[] image=seedImage(first);Set<String> before=libraries();openBackup();
        clickText("Export encrypted full backup","导出加密完整备份");awaitText("Backup password","备份口令");
        setEditor(0,PASSWORD);setEditor(1,PASSWORD);clickText("OK","确定");
        recreateBackupBehindPicker();saveInFixtureRoot();awaitNativeBackupStatus("Encrypted full backup exported","加密完整备份已导出");
        assertTrue(document(FULL).length>56);
        clickText("Restore encrypted full backup","恢复加密完整备份");awaitText("Backup password","备份口令");
        setEditor(0,PASSWORD);clickText("OK","确定");recreateBackupBehindPicker();openFixtureFile(FULL);
        awaitText("Restore encrypted full backup?","恢复加密完整备份？");
        awaitNativeBackupStatus("Full backup validated; waiting for your confirmation.","完整备份已验证，等待你的确认。");
        clickText("Create separate library","创建独立资料空间");
        String restored=awaitNewLibrary(before);awaitNativeBackupStatus("Full library restored separately; your default is unchanged.","完整资料已恢复到独立空间，默认库保持原样。");assertSearchable(restored,"alpha");
        List<XsMediaRepository.CompletedAsset> files=XsMediaRepository.get(app).snapshotCompleted(restored);
        assertEquals(1,files.size());assertArrayEquals(image,Files.readAllBytes(files.get(0).file.toPath()));
        assertEquals(defaultLibrary,XsLocalSpaces.current(app));testBodyCompleted=true;
    }

    @Test public void consolidationNativePreviewCancelConfirmAndUndoPreserveSourcesAndDefault()throws Exception {
        Set<String> before=libraries();
        screen=instrumentation.startActivitySync(XsLibrariesActivity.intent(app).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitText("Governance fixture A");clickText("Governance fixture A");clickText("Governance fixture B");setEditor(0,"Governance native merged fixture");
        clickConsolidationPreview();awaitText("Create a separate library?","创建独立资料空间？");
        assertTrue(activeText().contains("Governance native merged fixture"));clickText("Cancel","取消");
        awaitText("Canceled; no library created.","已取消；未创建资料空间。");assertEquals(before,libraries());
        clickConsolidationPreview();awaitText("Create a separate library?","创建独立资料空间？");clickText("Create library","确认创建");
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
    private void recreateBackupBehindPicker()throws Exception {
        await(this::documentsUiActive,"A real SAF request is pending before recreation");
        assertNull("A previous recreation must finish before requesting the next",pendingRecreation);
        assertTrue("The current backup owner is real and not destroyed",screen instanceof XsBackupActivity&&!screen.isDestroyed());
        pendingRecreation=new BackupRecreation(screen);
        instrumentation.runOnMainSync(screen::recreate);
        assertTrue("The recreation was requested while the actual system picker owned selection",documentsUiActive());
        // API26/27 may defer a stopped Activity's relaunch until SAF returns. Keep this
        // observer through the real result/cancel; never count an unchanged owner as a pass.
    }
    private final class BackupRecreation implements android.app.Application.ActivityLifecycleCallbacks {
        final Activity previous;final AtomicReference<Activity> replacement=new AtomicReference<>();
        final android.app.Application application=(android.app.Application)app.getApplicationContext();
        BackupRecreation(Activity previous){this.previous=previous;application.registerActivityLifecycleCallbacks(this);}
        public void onActivityCreated(Activity activity,Bundle state){if(activity instanceof XsBackupActivity&&activity!=previous){screen=activity;replacement.set(activity);}}
        public void onActivityStarted(Activity activity){}public void onActivityResumed(Activity activity){}
        public void onActivityPaused(Activity activity){}public void onActivityStopped(Activity activity){}
        public void onActivitySaveInstanceState(Activity activity,Bundle state){assertSavedStateHasNoPassword(state);}
        public void onActivityDestroyed(Activity activity){}
        void close(){application.unregisterActivityLifecycleCallbacks(this);}
    }
    private void awaitBackupRecreation(){
        BackupRecreation pending=pendingRecreation;if(pending==null)return;
        try {
            await(()->pending.replacement.get()!=null&&pending.previous.isDestroyed(),
                "The requested recreation produced an actual new backup owner before result verification");
            screen=pending.replacement.get();assertNotSame(pending.previous,screen);
            assertTrue("The old backup owner was actually destroyed",pending.previous.isDestroyed());
            assertFalse("The replacement backup owner is live",screen.isDestroyed());
        }finally{pending.close();pendingRecreation=null;}
    }
    private void awaitNativeBackupStatus(String en,String zh){
        await(()->{
            boolean[] shown={false};instrumentation.runOnMainSync(()->{
                assertTrue("Picker completion is verified on the actual replacement owner",screen instanceof XsBackupActivity&&!screen.isDestroyed());
                shown[0]=nativeBackupText(screen.getWindow().getDecorView(),en,zh);
            });return shown[0];
        },"The replacement backup Activity displays its own operation completion/preview status");
    }
    private boolean nativeBackupText(android.view.View view,String en,String zh){
        if(view instanceof android.widget.TextView){String value=((android.widget.TextView)view).getText().toString();if(value.contains(en)||value.contains(zh))return true;}
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(nativeBackupText(group.getChildAt(i),en,zh))return true;}
        return false;
    }
    private static void assertSavedStateHasNoPassword(Object value) {
        if(value instanceof Bundle) {
            Bundle state=(Bundle)value;for(String key:state.keySet()){assertFalse(key.contains(PASSWORD));assertSavedStateHasNoPassword(state.get(key));}
        }else if(value instanceof android.util.SparseArray) {
            android.util.SparseArray<?> state=(android.util.SparseArray<?>)value;
            for(int i=0;i<state.size();i++)assertSavedStateHasNoPassword(state.valueAt(i));
        }else if(value instanceof Object[]) {
            for(Object child:(Object[])value)assertSavedStateHasNoPassword(child);
        }else if(value instanceof char[]) {
            assertFalse(new String((char[])value).contains(PASSWORD));
        }else if(value instanceof CharSequence)assertFalse(value.toString().contains(PASSWORD));
    }
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
        await(this::documentsUiActive,"System DocumentsUI is active");
        // SAF often restores the exact provider directory itself. Do not toggle its drawer
        // and accidentally hit a different row while the sidebar animates into position.
        if(fixtureToolbarVisible()&&find(this::fixtureSidebarRoot)==null)return;
        if(find(this::fixtureSidebarRoot)==null) {
            AccessibilityNodeInfo navigation=awaitNode(n->systemDocumentNode(n)&&n.getClassName()!=null&&
                n.getClassName().toString().contains("ImageButton")&&n.getContentDescription()!=null&&
                (n.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT).contains("roots")||
                 n.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT).contains("navigation")||
                 n.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT).contains("drawer")));
            touchSystemDocument(navigation);
        }
        // Select only the sidebar ListView item. The same provider title also appears on
        // the Recent screen under "Browse files in other apps" and in the toolbar.
        activateFixtureSidebarRow();
        await(()->fixtureToolbarVisible()&&find(this::fixtureSidebarRoot)==null,
            "SAF shows the synthetic provider directory with its navigation drawer closed");
    }
    private void activateFixtureSidebarRow() {
        AccessibilityNodeInfo title=awaitNode(this::fixtureSidebarRoot);
        // Roots is an actual ListView item with a click delegate, unlike document
        // RecyclerView titles. Provider/package updates can rebind this adapter
        // between a synthetic DOWN and UP even when title bounds do not move.
        // Activate only the exact direct item containing our fixture title.
        for(int depth=0;title!=null&&depth<12;depth++) {
            AccessibilityNodeInfo parent=title.getParent();
            if(parent==null)break;
            String id=parent.getViewIdResourceName();CharSequence type=parent.getClassName();
            boolean rootList=(id!=null&&(id.equals("android:id/list")||id.endsWith(":id/roots_list")))||
                (type!=null&&type.toString().equals("android.widget.ListView"));
            if(rootList) {
                assertTrue("Only the fixture's actual clickable sidebar item is activated",title.refresh()&&systemDocumentNode(title)&&title.isClickable());
                assertNotNull("The refreshed sidebar item still names only our fixture provider",collect(title,
                    n->systemDocumentNode(n)&&GovernanceDocumentsProvider.TITLE.equals(text(n)),new int[]{0}));
                AccessibilityNodeInfo active=instrumentation.getUiAutomation().getRootInActiveWindow();
                assertTrue("The system picker remains the active window",active!=null&&systemDocumentNode(active));
                Rect row=new Rect(),window=new Rect();title.getBoundsInScreen(row);active.getBoundsInScreen(window);
                assertTrue("The fixture sidebar item is inside the active picker",row.intersect(window)&&!row.isEmpty());
                android.util.Log.i("XsGovernanceFixture","activate exact fixture roots-list item bounds="+row);
                assertTrue("Activate the actual system fixture root item",title.performAction(AccessibilityNodeInfo.ACTION_CLICK));
                return;
            }
            title=parent;
        }
        throw new AssertionError("The fixture title has no direct system roots-list item");
    }
    private boolean fixtureSidebarRoot(AccessibilityNodeInfo node){
        if(!systemDocumentNode(node)||!GovernanceDocumentsProvider.TITLE.equals(text(node)))return false;
        for(int depth=0;node!=null&&depth<12;depth++,node=node.getParent()) {
            String id=node.getViewIdResourceName();CharSequence type=node.getClassName();
            if((id!=null&&(id.equals("android:id/list")||id.endsWith(":id/roots_list")))||
                    (type!=null&&type.toString().equals("android.widget.ListView")))return true;
        }return false;
    }
    private boolean fixtureToolbarVisible(){return find(n->{
        if(!systemDocumentNode(n)||!GovernanceDocumentsProvider.TITLE.equals(text(n)))return false;
        for(int depth=0;n!=null&&depth<10;depth++,n=n.getParent()) {
            String id=n.getViewIdResourceName();CharSequence type=n.getClassName();
            if((id!=null&&id.endsWith(":id/toolbar"))||(type!=null&&type.toString().endsWith(".Toolbar")))return true;
        }return false;
    })!=null;}
    private static boolean systemDocumentNode(AccessibilityNodeInfo node){return node.isVisibleToUser()&&node.isEnabled()&&node.getPackageName()!=null&&node.getPackageName().toString().contains("documentsui");}
    private void touchSystemDocument(AccessibilityNodeInfo node) {
        assertTrue("Only a visible system document control is touched",systemDocumentNode(node));
        final String expectedText=text(node),expectedId=node.getViewIdResourceName(),expectedClass=String.valueOf(node.getClassName()),expectedDescription=String.valueOf(node.getContentDescription());
        final boolean sidebar=fixtureSidebarRoot(node);
        // Visible nodes can already be exposed while DrawerLayout is still moving. Wait
        // for an idle frame and fresh, clipped bounds that remain stable across frames.
        try{instrumentation.getUiAutomation().waitForIdle(100,1000);}catch(java.util.concurrent.TimeoutException backgroundEvents){/* Geometry below is still required to stabilize. */}
        Rect previous=null,bounds=null;long stableSince=0,deadline=SystemClock.elapsedRealtime()+2000;
        while(SystemClock.elapsedRealtime()<deadline) {
            AccessibilityNodeInfo fresh=find(n->systemDocumentNode(n)&&expectedText.equals(text(n))&&
                java.util.Objects.equals(expectedId,n.getViewIdResourceName())&&expectedClass.equals(String.valueOf(n.getClassName()))&&
                expectedDescription.equals(String.valueOf(n.getContentDescription()))&&(!sidebar||fixtureSidebarRoot(n)));
            AccessibilityNodeInfo active=instrumentation.getUiAutomation().getRootInActiveWindow();
            if(fresh!=null&&fresh.refresh()&&active!=null&&active.getPackageName()!=null&&active.getPackageName().toString().contains("documentsui")) {
                Rect candidate=new Rect(),window=new Rect();fresh.getBoundsInScreen(candidate);active.getBoundsInScreen(window);
                if(candidate.intersect(window)&&!candidate.isEmpty()) {
                    if(candidate.equals(previous)) {if(SystemClock.elapsedRealtime()-stableSince>=100){bounds=candidate;break;}}
                    else {previous=candidate;stableSince=SystemClock.elapsedRealtime();}
                }
            }
            SystemClock.sleep(16);
        }
        assertNotNull("System document control has stable bounds inside its active window",bounds);
        android.util.Log.i("XsGovernanceFixture","touch text="+expectedText+" id="+expectedId+" class="+expectedClass+" bounds="+bounds+" sidebar="+sidebar);
        MotionEvent.PointerProperties pointer=new MotionEvent.PointerProperties();pointer.id=0;pointer.toolType=MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords point=new MotionEvent.PointerCoords();point.x=bounds.exactCenterX();point.y=bounds.exactCenterY();point.pressure=1;point.size=1;
        long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,1,new MotionEvent.PointerProperties[]{pointer},new MotionEvent.PointerCoords[]{point},0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        assertEquals(MotionEvent.TOOL_TYPE_FINGER,down.getToolType(0));
        try{assertTrue("Touch system document control",instrumentation.getUiAutomation().injectInputEvent(down,true));}finally{down.recycle();}
        SystemClock.sleep(20);
        point.pressure=0;
        MotionEvent up=MotionEvent.obtain(now,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,1,new MotionEvent.PointerProperties[]{pointer},new MotionEvent.PointerCoords[]{point},0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try{assertTrue("Release system document control",instrumentation.getUiAutomation().injectInputEvent(up,true));}finally{up.recycle();}
    }
    private void saveFailureArtifacts() {
        // Only this synthetic host is installed by the CI suite. Capture before canceling
        // the picker; password field text stays redacted in the bounded AX diagnostic.
        try {
            if(!"1".equals(fixtureShell("getprop ro.kernel.qemu").trim()))return;
            String name=testName.getMethodName();
            if(!Arrays.asList("metadataSafExportPreviewCancelAndConfirmUseActualDocumentBytes",
                    "encryptedFullSafRoundTripValidatesPasswordAndRestoresActualImage",
                    "consolidationNativePreviewCancelConfirmAndUndoPreserveSourcesAndDefault",
                    "assetsMultiSelectSafZipContainsEverySelectedOriginalFile",
                    "pendingMetadataSafRequestsSurviveRecreationAndCancellation",
                    "pendingStaleMetadataPickerCannotWriteAfterLibraryBarrier",
                    "pendingEncryptedSafRequestsRetainPasswordOnlyInMemoryAcrossRecreation").contains(name))return;
            File directory=app.getExternalFilesDir("xs-governance-ui");if(directory==null||(!directory.isDirectory()&&!directory.mkdirs()))return;
            StringBuilder ax=new StringBuilder();
            for(android.view.accessibility.AccessibilityWindowInfo window:instrumentation.getUiAutomation().getWindows()) {
                Rect rect=new Rect();window.getBoundsInScreen(rect);ax.append("WINDOW type=").append(window.getType()).append(" id=").append(window.getId()).append(" active=").append(window.isActive()).append(" focused=").append(window.isFocused()).append(" bounds=").append(rect).append('\n');
            }
            collect(instrumentation.getUiAutomation().getRootInActiveWindow(),n->{Rect rect=new Rect();n.getBoundsInScreen(rect);
                String value=text(n).replace('\n',' ').replace('\t',' ');if(value.length()>256)value=value.substring(0,256);
                ax.append(n.getClassName()).append('\t').append(n.getViewIdResourceName()).append('\t').append(rect).append(" visible=").append(n.isVisibleToUser()).append(" enabled=").append(n.isEnabled()).append(" clickable=").append(n.isClickable()).append('\t').append(value).append('\n');return false;},new int[]{0});
            Files.write(new File(directory,name+".ax.txt").toPath(),ax.toString().getBytes(StandardCharsets.UTF_8));
            Bitmap shot=instrumentation.getUiAutomation().takeScreenshot();if(shot!=null)try(FileOutputStream out=new FileOutputStream(new File(directory,name+".png"))){shot.compress(Bitmap.CompressFormat.PNG,100,out);}finally{shot.recycle();}
            // UTP uninstalls the target and removes its external files before Gradle returns.
            // Preserve only these generated diagnostics in the disposable shell-owned folder.
            fixtureShell("mkdir -p /data/local/tmp/xs-governance-ui");
            fixtureShell("chmod 700 /data/local/tmp/xs-governance-ui");
            for(String extension:new String[]{".ax.txt",".png"}) {
                File generated=new File(directory,name+extension);
                if(generated.isFile())fixtureShell("cp "+generated.getAbsolutePath()+" /data/local/tmp/xs-governance-ui/"+name+extension);
            }
        }catch(Exception unavailable){android.util.Log.i("XsGovernanceFixture","Failure capture unavailable");}
    }
    private String fixtureShell(String command)throws java.io.IOException {
        try(android.os.ParcelFileDescriptor.AutoCloseInputStream output=new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.getUiAutomation().executeShellCommand(command))) {
            ByteArrayOutputStream captured=new ByteArrayOutputStream();byte[] bytes=new byte[1024];int count;
            while((count=output.read(bytes))!=-1)if(captured.size()<8192)captured.write(bytes,0,Math.min(count,8192-captured.size()));
            return new String(captured.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private boolean documentsUiActive(){return find(n->n.getPackageName()!=null&&n.getPackageName().toString().contains("documentsui"))!=null;}
    private void saveInFixtureRoot(){
        fixtureRoot();
        AccessibilityNodeInfo save=awaitNode(this::fixtureSaveButton);
        // On API26 the IME can cover the save form in the accessibility window
        // geometry even while its adjusted position is visible in the screenshot.
        // Close only a real input-method window over this fixed create-document
        // form; an unguarded Back could otherwise cancel SAF itself.
        if(!save.isVisibleToUser()&&inputMethodWindowVisible()) {
            AccessibilityNodeInfo filename=find(this::fixtureExportFilename);
            assertTrue("The keyboard belongs to the confirmed synthetic create-document form",
                fixtureToolbarVisible()&&find(this::fixtureSidebarRoot)==null&&filename!=null&&save.refresh()&&fixtureSaveButton(save));
            String intendedName=text(filename);
            android.util.Log.i("XsGovernanceFixture","Dismiss actual IME over fixed SAF save form: "+intendedName);
            assertTrue("One real system Back dismisses the observed keyboard",
                instrumentation.getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK));
            await(()->!inputMethodWindowVisible()&&documentsUiActive()&&fixtureToolbarVisible()&&find(this::fixtureSidebarRoot)==null&&
                find(n->fixtureExportFilename(n)&&intendedName.equals(text(n)))!=null,
                "The keyboard closes while the same synthetic save form remains active");
        }
        awaitNode(n->fixtureSaveButton(n)&&n.isVisibleToUser());
        clickText("Save","保存");await(()->!documentsUiActive(),"SAF returned the saved document");awaitBackupRecreation();
    }
    private boolean fixtureSaveButton(AccessibilityNodeInfo node){
        return documentSaveFormNode(node)&&"android:id/button1".equals(node.getViewIdResourceName())&&node.isClickable()&&
            ("Save".equalsIgnoreCase(text(node))||"保存".equals(text(node)));
    }
    private boolean fixtureExportFilename(AccessibilityNodeInfo node){
        return documentSaveFormNode(node)&&"android:id/title".equals(node.getViewIdResourceName())&&isEditor(node)&&
            (METADATA.equals(text(node))||FULL.equals(text(node))||"Xs-selected-media.zip".equals(text(node)));
    }
    private boolean documentSaveFormNode(AccessibilityNodeInfo node){
        if(!node.isEnabled()||node.getPackageName()==null||!node.getPackageName().toString().contains("documentsui"))return false;
        for(int depth=0;node!=null&&depth<10;depth++,node=node.getParent()) {
            String id=node.getViewIdResourceName();if(id!=null&&id.endsWith(":id/container_save"))return true;
        }
        return false;
    }
    private boolean inputMethodWindowVisible(){
        for(android.view.accessibility.AccessibilityWindowInfo window:instrumentation.getUiAutomation().getWindows()) {
            Rect bounds=new Rect();window.getBoundsInScreen(bounds);
            if(window.getType()==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD&&!bounds.isEmpty())return true;
        }
        return false;
    }
    private void openFixtureFile(String name){
        assertTrue("Only fixed synthetic backup names may be selected",METADATA.equals(name)||FULL.equals(name));
        fixtureRoot();useFixtureListView();
        // A RecyclerView ancestor may report ACTION_CLICK success without activating its
        // child document. The user's file-title tap goes through SAF's gesture handler.
        touchSystemDocument(awaitNode(n->systemDocumentNode(n)&&!isEditor(n)&&name.equals(text(n))));
        await(()->!documentsUiActive(),"SAF returned the selected document");awaitBackupRecreation();
    }
    private void useFixtureListView(){
        assertTrue("Only the confirmed fixture directory changes presentation",confirmedFixtureDirectory());
        try{instrumentation.getUiAutomation().waitForIdle(100,1000);}
        catch(java.util.concurrent.TimeoutException backgroundEvents){/* Fresh exact system actions remain required below. */}
        AccessibilityNodeInfo mode=awaitNode(n->fixtureViewAction(n,true)||fixtureViewAction(n,false));
        if(fixtureViewAction(mode,true)) {
            android.util.Log.i("XsGovernanceFixture","Switch fixed document directory through its actual List view action");
            touchSystemDocument(mode);
        }
        await(()->confirmedFixtureDirectory()&&find(n->fixtureViewAction(n,false))!=null,
            "Actual List view is confirmed by the visible opposite Grid view action");
    }
    private boolean confirmedFixtureDirectory(){return documentsUiActive()&&fixtureToolbarVisible()&&find(this::fixtureSidebarRoot)==null;}
    private boolean fixtureViewAction(AccessibilityNodeInfo node,boolean list){
        if(!systemDocumentNode(node)||!node.isClickable())return false;
        String id=node.getViewIdResourceName(),suffix=list?"list":"grid",label=list?"List view":"Grid view";
        // Official DocumentsUI resources: API26 menu_*, API27 option_menu_*,
        // modern picker sub_menu_*. Require the exact resource and action label.
        return id!=null&&(id.endsWith(":id/menu_"+suffix)||id.endsWith(":id/option_menu_"+suffix)||id.endsWith(":id/sub_menu_"+suffix))&&
            (label.equals(text(node))||label.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()));
    }
    private void clickConsolidationPreview(){
        AccessibilityNodeInfo preview=awaitNode(n->nativeFixtureNode(n)&&String.valueOf(n.getClassName()).endsWith("Button")&&
            ("Check & preview".equals(text(n))||"检查并预览".equals(text(n))));
        if(!preview.isVisibleToUser()&&inputMethodWindowVisible()) {
            assertTrue("Only the fixed native consolidation form may dismiss its actual keyboard",fixedConsolidationForm());
            android.util.Log.i("XsGovernanceFixture","Dismiss actual IME over fixed native consolidation form; name and two sources verified");
            assertTrue("One real system Back dismisses the observed consolidation keyboard",
                instrumentation.getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK));
            await(()->!inputMethodWindowVisible()&&fixedConsolidationForm(),
                "The keyboard closes while the fixed name and exactly the same two checked sources remain");
        }
        // Retain the original show-on-screen, visible-node and real native-click path.
        clickText("Check & preview","检查并预览");
    }
    private boolean nativeFixtureNode(AccessibilityNodeInfo node){
        return node.isEnabled()&&node.getPackageName()!=null&&app.getPackageName().contentEquals(node.getPackageName());
    }
    private boolean fixedConsolidationForm(){
        AccessibilityNodeInfo active=instrumentation.getUiAutomation().getRootInActiveWindow();
        if(!(screen instanceof XsLibrariesActivity)||active==null||!nativeFixtureNode(active))return false;
        if(find(n->nativeFixtureNode(n)&&("Combine into a separate library".equals(text(n))||"合并到独立资料空间".equals(text(n))))==null)return false;
        if(find(n->nativeFixtureNode(n)&&isEditor(n)&&"Governance native merged fixture".equals(text(n)))==null)return false;
        List<String> checked=new ArrayList<>();
        collect(active,n->{if(nativeFixtureNode(n)&&String.valueOf(n.getClassName()).endsWith("CheckBox")&&n.isCheckable()&&n.isChecked())checked.add(text(n));return false;},new int[]{0});
        return checked.size()==2&&checked.contains("Governance fixture A")&&checked.contains("Governance fixture B");
    }
    private void setEditor(int index,String value) {
        List<AccessibilityNodeInfo> fields=new ArrayList<>();AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();collect(root,n->{if(isEditor(n)&&n.isVisibleToUser())fields.add(n);return false;},new int[]{0});
        assertTrue("Native editor "+index+" is visible",fields.size()>index);Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
        assertTrue("Native editor accepts input",fields.get(index).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args));
    }
    private static boolean isEditor(AccessibilityNodeInfo n){return n.getClassName()!=null&&n.getClassName().toString().contains("EditText");}
    private void clickText(String... alternatives) {
        Predicate<AccessibilityNodeInfo> matches=n->{if(!n.isEnabled())return false;String text=text(n);for(String option:alternatives)if(text.equalsIgnoreCase(option))return true;return false;};
        AccessibilityNodeInfo node=awaitNode(matches);
        // The source list is scrollable and the IME can reduce its visible height on API27.
        // Request the actual control into view before requiring visible accessibility state.
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        node=awaitNode(n->matches.test(n)&&n.isVisibleToUser());
        AccessibilityNodeInfo original=node;
        for(int i=0;i<5&&node!=null;i++,node=node.getParent())if(node.isClickable()){assertTrue("Click native UI "+Arrays.toString(alternatives),node.performAction(AccessibilityNodeInfo.ACTION_CLICK));return;}
        // DocumentsUI's RecyclerView can expose non-clickable title/row nodes while its
        // touch listener owns selection. Tap only its freshly observed visible node bounds.
        if(original.getPackageName()!=null&&original.getPackageName().toString().contains("documentsui")) {
            touchSystemDocument(original);return;
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
