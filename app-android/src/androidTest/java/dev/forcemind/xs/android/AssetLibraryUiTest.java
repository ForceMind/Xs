package dev.forcemind.xs.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Generated local assets and native UI only; no X/recipient-app or physical-device claims. */
@RunWith(AndroidJUnit4.class)
public final class AssetLibraryUiTest {
    private Instrumentation instrumentation;private Context app;private XsMediaRepository repo;private XsArchiveDatabase archive;
    private String library,other,previousDefault;private Activity screen;
    @Before public void setup(){
        instrumentation=InstrumentationRegistry.getInstrumentation();app=instrumentation.getTargetContext();repo=XsMediaRepository.get(app);archive=new XsArchiveDatabase(app);
        AccessibilityServiceInfo info=instrumentation.getUiAutomation().getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;instrumentation.getUiAutomation().setServiceInfo(info);
        library="local:ui-"+UUID.randomUUID();other="local:ui-other-"+UUID.randomUUID();previousDefault=XsLocalSpaces.current(app);
        archive.ensureLibrary(library,"UI fixture library");archive.ensureLibrary(other,"Other fixture library");
    }
    @After public void cleanup()throws Exception {
        if(screen!=null){instrumentation.runOnMainSync(screen::finish);instrumentation.waitForIdleSync();}
        for(String scope:new String[]{library,other}){
            if(!XsLibraryWrites.isDeleted(scope)){
                CountDownLatch done=new CountDownLatch(1);assertTrue(XsLibraryWrites.invalidateAndSubmit(scope,()->{try{repo.eraseLibrary(scope);archive.deleteLibraryScope(scope);}finally{done.countDown();}}));assertTrue(done.await(10,TimeUnit.SECONDS));
            }
        }
        android.content.SharedPreferences.Editor preference=app.getSharedPreferences("xs_manual_archive",Context.MODE_PRIVATE).edit();
        if(previousDefault==null)preference.remove("local_space");else preference.putString("local_space",previousDefault);assertTrue(preference.commit());archive.close();
    }
    private String fixture(String scope,String category,String post)throws Exception {
        String id=UUID.randomUUID().toString(),filename=id+".png";File root=new File(app.getFilesDir(),"xs-saved-media");assertTrue(root.isDirectory()||root.mkdirs());File file=new File(root,filename);
        Bitmap image=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888);image.eraseColor(0xff389955);try(FileOutputStream output=new FileOutputStream(file)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));}image.recycle();
        ContentValues v=new ContentValues();v.put("id",id);v.put("library_id",scope);v.put("post_url","https://x.com/i/web/status/"+post);v.put("state","COMPLETED");v.put("mime","image/png");v.put("filename",filename);v.put("category",category);v.put("bytes",file.length());v.put("total",file.length());v.put("created_ms",System.currentTimeMillis());repo.getWritableDatabase().insertOrThrow("assets",null,v);return id;
    }
    @Test public void actualAssetsUiSearchesThenBatchCategorizesAndDeletesOnlySelected()throws Exception {
        String first=fixture(library,"FindAsset","980"),second=fixture(library,"FindAsset","981"),kept=fixture(library,"KeepAsset","982"),outside=fixture(other,"FindAsset","983");
        archive.favorite(library,"https://x.com/i/web/status/980","alice","Keep favorite history",null,System.currentTimeMillis());
        screen=instrumentation.startActivitySync(XsAssetsActivity.intent(app,library).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrumentation.waitForIdleSync();awaitCount(3);
        instrumentation.runOnMainSync(()->((EditText)screen.findViewById(XsAssetsActivity.ID_SEARCH)).setText("FindAsset"));awaitCount(2);
        instrumentation.runOnMainSync(()->{ListView list=screen.findViewById(XsAssetsActivity.ID_RESULTS);for(int i=0;i<2;i++)list.performItemClick(list.getChildAt(i),i,list.getAdapter().getItemId(i));});
        click(XsAssetsActivity.ID_ACTIONS);clickText(label("批量分类","Set category"));setText(label("资源分类","Asset category"),"ReviewedAsset");dialogButton(android.R.id.button1);
        await("both asset categories committed",()->"ReviewedAsset".equals(repo.find(library,first).category)&&"ReviewedAsset".equals(repo.find(library,second).category));awaitCount(0);
        instrumentation.runOnMainSync(()->((EditText)screen.findViewById(XsAssetsActivity.ID_SEARCH)).setText("ReviewedAsset"));awaitCount(2);
        click(XsAssetsActivity.ID_ACTIONS);clickText(label("删除所选资源 / 任务","Delete selected assets / tasks"));dialogButton(android.R.id.button1);
        await("selected asset metadata gone",()->repo.find(library,first)==null&&repo.find(library,second)==null);awaitCount(0);
        assertNotNull(repo.find(library,kept));assertNotNull(repo.find(other,outside));
        try(Cursor c=archive.search(library,new dev.forcemind.xs.core.ArchiveSearch.Filter())){assertEquals("Asset deletion preserves posts and favorites",1,c.getCount());}
    }
    @Test public void wholeLibraryUiRequiresTypedConfirmationAndPreservesOtherScopeAndShareLease()throws Exception {
        assertTrue(app.getSharedPreferences("xs_manual_archive",Context.MODE_PRIVATE).edit().putString("local_space",library).commit());
        archive.recordObservedOpen(library,"https://x.com/i/web/status/984","alice","delete scope snapshot",null,System.currentTimeMillis(),"delete-ui-open");archive.favorite(other,"https://x.com/i/web/status/985","bob","outside favorite",null,System.currentTimeMillis());
        String removed=fixture(library,"Erase","984"),kept=fixture(other,"Retain","985");File original=repo.completedFile(library,removed);byte[] expected=java.nio.file.Files.readAllBytes(original.toPath());
        Uri lease=XsShareCoordinator.assetIntent(app,library,Arrays.asList(removed)).getParcelableExtra(Intent.EXTRA_STREAM);XsLibraryWrites.Ticket stale=XsLibraryWrites.ticket(library);
        screen=instrumentation.startActivitySync(XsLibraryManagementActivity.intent(app,library).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrumentation.waitForIdleSync();await("delete entry enabled",()->{final boolean[] enabled={false};instrumentation.runOnMainSync(()->enabled[0]=screen.findViewById(XsLibraryManagementActivity.ID_DELETE).isEnabled());return enabled[0];});
        click(XsLibraryManagementActivity.ID_DELETE);AccessibilityNodeInfo positive=dialogNode(android.R.id.button1,false);assertFalse("Deletion requires exact typed confirmation",positive.isEnabled());
        setText(label("输入“删除”确认","Type DELETE to confirm"),label("删除","DELETE"));dialogButton(android.R.id.button1);
        await("whole library deleted",()->{final String[] status={""};instrumentation.runOnMainSync(()->status[0]=((TextView)screen.findViewById(XsLibraryManagementActivity.ID_STATUS)).getText().toString());return status[0].equals(label("资料空间已删除；其他空间和网页会话保留。","Library deleted; other libraries and web sessions remain."));});
        assertTrue(XsDeletedLibraries.isDeleted(app,library));assertFalse(XsDeletedLibraries.pending(app).contains(library));assertTrue(XsLibraryWrites.isDeleted(library));
        AtomicBoolean revived=new AtomicBoolean();assertFalse(XsLibraryWrites.submit(stale,()->revived.set(true)));assertFalse(revived.get());
        assertNotEquals(library,XsLocalSpaces.defaultLibrary(app));assertFalse(original.exists());assertNull(repo.find(library,removed));assertNotNull(repo.find(other,kept));
        try(Cursor rows=archive.search(library,new dev.forcemind.xs.core.ArchiveSearch.Filter())){assertEquals(0,rows.getCount());}try(Cursor rows=archive.search(other,new dev.forcemind.xs.core.ArchiveSearch.Filter())){assertEquals(1,rows.getCount());}
        try(java.io.InputStream input=app.getContentResolver().openInputStream(lease);java.io.ByteArrayOutputStream received=new java.io.ByteArrayOutputStream()){assertNotNull(input);byte[] buffer=new byte[1024];int n;while((n=input.read(buffer))!=-1)received.write(buffer,0,n);assertArrayEquals("Delayed receiver gets every selected byte after whole-library deletion",expected,received.toByteArray());}
    }
    @Test public void failedFilePurgeRemainsRevokedAndStartupRetryCompletesOnlyPendingScope()throws Exception {
        String id=fixture(library,"Retry","986");File original=repo.completedFile(library,id);assertTrue(original.delete());assertTrue(original.mkdir());File blocker=new File(original,"blocker");assertTrue(blocker.createNewFile());
        CountDownLatch result=new CountDownLatch(1);AtomicBoolean success=new AtomicBoolean(true);XsLibraryDeletion.delete(app,library,(ok,replacement)->{success.set(ok);result.countDown();});assertTrue(result.await(10,TimeUnit.SECONDS));assertFalse(success.get());
        assertTrue(XsDeletedLibraries.pending(app).contains(library));assertTrue(XsLibraryWrites.isDeleted(library));assertNotNull(repo.find(library,id));
        assertTrue(blocker.delete());assertTrue(original.delete());XsDeletedLibraries.initialize(app);XsLibraryDeletion.retryPending(app);
        await("pending deletion retry finished",()->!XsDeletedLibraries.pending(app).contains(library));assertNull(repo.find(library,id));assertTrue(XsDeletedLibraries.isDeleted(app,library));
        try(Cursor rows=archive.libraries()){boolean preserved=false;while(rows.moveToNext())if(other.equals(rows.getString(3)))preserved=true;assertTrue("Retry preserves unrelated library",preserved);}
    }
    @Test public void failedPreferenceCommitRestoresCachedSetsWithoutRevokingWriter()throws Exception {
        final java.util.Map<String,Object> stored=new java.util.HashMap<>();
        stored.put("deleted",new java.util.HashSet<>(Arrays.asList("previously-deleted")));
        stored.put("pending",new java.util.HashSet<>(Arrays.asList("previously-deleted")));
        android.content.SharedPreferences failed=failedCommitPreferences(stored);
        Context failingContext=new android.content.ContextWrapper(app){
            @Override public Context getApplicationContext(){return this;}
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return failed;}
        };
        XsLibraryWrites.Ticket current=XsLibraryWrites.ticket(library);
        try{XsDeletedLibraries.markDeleted(failingContext,library);fail("Failed commit accepted");}catch(IllegalStateException expected){}
        assertFalse("No cached false tombstone",XsDeletedLibraries.isDeleted(failingContext,library));
        assertEquals(new java.util.HashSet<>(Arrays.asList("previously-deleted")),XsDeletedLibraries.pending(failingContext));
        assertFalse("Writer must remain open when durable request failed",XsLibraryWrites.isDeleted(library));assertTrue(XsLibraryWrites.isCurrent(current));
        CountDownLatch written=new CountDownLatch(1);assertTrue(XsLibraryWrites.submit(current,written::countDown));assertTrue(written.await(5,TimeUnit.SECONDS));
        try{XsDeletedLibraries.completeDeletion(failingContext,"previously-deleted");fail("Failed completion commit accepted");}catch(IllegalStateException expected){}
        assertTrue("Pending cleanup remains retryable after commit failure",XsDeletedLibraries.pending(failingContext).contains("previously-deleted"));
    }
    /** Models Android's commit-to-memory occurring before a failed durable disk write. */
    private static android.content.SharedPreferences failedCommitPreferences(java.util.Map<String,Object> values){
        return (android.content.SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences.class.getClassLoader(),new Class<?>[]{android.content.SharedPreferences.class},(proxy,method,args)->{
            if(method.getName().equals("getStringSet")){Object value=values.get((String)args[0]);return value==null?args[1]:new java.util.HashSet<>((java.util.Set<String>)value);}
            if(method.getName().equals("edit")){
                java.util.Map<String,Object> changes=new java.util.HashMap<>();
                return java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{android.content.SharedPreferences.Editor.class},(editor,action,parameters)->{
                    if(action.getName().equals("putStringSet")){changes.put((String)parameters[0],new java.util.HashSet<>((java.util.Set<String>)parameters[1]));return editor;}
                    if(action.getName().equals("commit")){values.putAll(changes);return false;}
                    throw new UnsupportedOperationException(action.getName());
                });
            }
            throw new UnsupportedOperationException(method.getName());
        });
    }
    private void click(int id){instrumentation.runOnMainSync(()->assertTrue(screen.findViewById(id).performClick()));instrumentation.waitForIdleSync();}
    private void awaitCount(int expected){await("asset result count "+expected,()->{final int[] actual={-1};instrumentation.runOnMainSync(()->actual[0]=((ListView)screen.findViewById(XsAssetsActivity.ID_RESULTS)).getAdapter().getCount());return actual[0]==expected;});}
    private AccessibilityNodeInfo textNode(String label){final AccessibilityNodeInfo[] found={null};await("accessible "+label,()->{AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();if(root==null)return false;for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText(label)){if(label.contentEquals(n.getText()==null?"":n.getText())||label.contentEquals(n.getContentDescription()==null?"":n.getContentDescription())){found[0]=n;return true;}}return false;});return found[0];}
    private void clickText(String label){assertTrue(textNode(label).performAction(AccessibilityNodeInfo.ACTION_CLICK));instrumentation.waitForIdleSync();}
    private void setText(String label,String value){Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);assertTrue(textNode(label).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args));instrumentation.waitForIdleSync();}
    private AccessibilityNodeInfo dialogNode(int id,boolean enabled){final AccessibilityNodeInfo[] found={null};String resource=app.getResources().getResourceName(id);await("dialog control "+resource,()->{AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();if(root==null)return false;for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByViewId(resource))if(node.isVisibleToUser()&&(!enabled||node.isEnabled())){found[0]=node;return true;}return false;});return found[0];}
    private void dialogButton(int id){assertTrue(dialogNode(id,true).performAction(AccessibilityNodeInfo.ACTION_CLICK));instrumentation.waitForIdleSync();}
    private interface Check{boolean passed();}
    private void await(String expected,Check condition){long deadline=SystemClock.uptimeMillis()+7000;do{if(condition.passed())return;SystemClock.sleep(30);}while(SystemClock.uptimeMillis()<deadline);fail("Timed out waiting for "+expected);}
    private String label(String zh,String en){return app.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")?zh:en;}
}
