package dev.forcemind.xs.android;

import android.content.Context;
import android.database.Cursor;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import dev.forcemind.xs.core.ArchiveSearch;
import dev.forcemind.xs.core.SnapshotPolicy;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Execute current/history/version multiword search on each device's actual SQLite/FTS4 build. */
@RunWith(AndroidJUnit4.class)
public final class FtsCompatibilityIntegrationTest {
    @Test public void implicitAndFindsCurrentAndHistoricalTextWithLiteralOperatorTokens(){
        Context app=ApplicationProvider.getApplicationContext();String library="local:fts-"+UUID.randomUUID();
        String url="https://x.com/alice/status/777800";
        try(XsArchiveDatabase db=new XsArchiveDatabase(app)) {
            try {
                db.ensureLibrary(library,"FTS compatibility fixture");
                assertTrue(db.favorite(library,url,"alice","native resource fixture",null,100));
                db.putFavoriteDetails(library,"777800","retained note","compat");db.putCategory(library,"keep","Keep");assertTrue(db.assignCategory(library,"777800","keep"));
                ArchiveSearch.Filter q=new ArchiveSearch.Filter();q.keyword="native resource";q.favoriteOnly=true;q.categoryId="keep";q.tag="compat";
                try(Cursor c=db.search(library,q)){assertEquals("Native SQLite must find both ordinary words",1,c.getCount());}
                q.keyword="native\tresource";try(Cursor c=db.search(library,q)){assertEquals(1,c.getCount());}
                q.keyword="native OR resource";try(Cursor c=db.search(library,q)){assertEquals("User OR is a literal prefix, never an operator",0,c.getCount());}
                assertTrue(db.recordSnapshot(library,url,"alice","short complete body",null,200,SnapshotPolicy.Source.POST_DETAIL,SnapshotPolicy.Quality.COMPLETE));
                q.keyword="native resource";try(Cursor c=db.search(library,q)){assertEquals(0,c.getCount());}
                q.includeSnapshotVersions=true;try(Cursor c=db.search(library,q)){assertEquals("Historical MATCH shares the portable AND grammar",1,c.getCount());}
                try(Cursor c=db.searchSnapshots(library,"777800","native resource",50,0)){assertEquals(1,c.getCount());}
            } finally {db.deleteLibraryScope(library);}
        }
    }
}
