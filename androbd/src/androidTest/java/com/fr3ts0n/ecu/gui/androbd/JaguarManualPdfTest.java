package com.fr3ts0n.ecu.gui.androbd;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class JaguarManualPdfTest {
    @Test public void bundledOriginalHasAllPagesAndRendersEachSection() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file=File.createTempFile("manual-test-", ".pdf",context.getCacheDir());
        try {
            try(InputStream in=context.getAssets().open("jaguar_manual/STYPE.pdf");OutputStream out=new FileOutputStream(file)) {
                byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);
            }
            try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
                PdfRenderer renderer=new PdfRenderer(fd)) {
                assertEquals(309,renderer.getPageCount());
                for(int index:new int[]{0,1,61,122,196,233,267,282,299,308}) {
                    try(PdfRenderer.Page page=renderer.openPage(index)) {
                        Bitmap bitmap=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888);
                        try {page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);}
                        finally {bitmap.recycle();}
                    }
                }
            }
        } finally {file.delete();}
    }
}
