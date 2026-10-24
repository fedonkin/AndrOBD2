package com.fr3ts0n.ecu.gui.androbd;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Exact source-page rendering, including diagrams/table alignment. No network or ECU actions. */
public class JaguarManualPageActivity extends AppCompatActivity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private int page=1, width=1200;
    private volatile int generation;
    private ImageView image;
    private TextView status;
    private volatile boolean closed;
    @Override public void onCreate(Bundle state) {
        SettingsActivity.applyLocale(this); super.onCreate(state); setTitle(R.string.jag_manual_title);
        page=Math.max(1,Math.min(309,state!=null?state.getInt("page",1):getIntent().getIntExtra("page",1)));
        if(state!=null) width=state.getInt("width",1200);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout controls=new LinearLayout(this);
        add(controls,"‹",()->finish());add(controls,"−",()->{width=Math.max(600,width-300);render();});
        add(controls,"+",()->{width=Math.min(2400,width+300);render();});
        add(controls,"◀",()->{page=Math.max(1,page-1);render();});
        add(controls,"▶",()->{page=Math.min(309,page+1);render();});root.addView(controls);
        status=new TextView(this);status.setPadding(16,8,16,8);root.addView(status);
        ScrollView vertical=new ScrollView(this); HorizontalScrollView horizontal=new HorizontalScrollView(this);
        image=new ImageView(this);image.setAdjustViewBounds(true);
        horizontal.addView(image,new HorizontalScrollView.LayoutParams(-2,-2));vertical.addView(horizontal);
        root.addView(vertical,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        ModernUiUtils.applyEdgeToEdgeInsets(this);render();
    }
    private void add(LinearLayout parent,String title,Runnable run) {
        Button b=new Button(this);b.setText(title);b.setOnClickListener(v->run.run());parent.addView(b,new LinearLayout.LayoutParams(0,-2,1));
    }
    private void render() {
        final int token=++generation, requested=page, pixels=width;
        status.setText(getString(R.string.jag_manual_page,requested,309));
        worker.execute(()->{
            Bitmap bitmap=null;
            File temporary=null;
            try {
                if(closed || token!=generation)return;
                File file=File.createTempFile("jaguar-stype-", ".pdf", getCacheDir());
                temporary=file;
                // Recopy from the shipped source: never display a stale PDF after app update.
                try(InputStream in=getAssets().open("jaguar_manual/STYPE.pdf");OutputStream out=new FileOutputStream(file)) {
                    byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                }
                try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
                    PdfRenderer renderer=new PdfRenderer(fd);PdfRenderer.Page p=renderer.openPage(requested-1)) {
                    int height=Math.round(pixels*(float)p.getHeight()/p.getWidth());
                    bitmap=Bitmap.createBitmap(pixels,height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);
                    p.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                }
                final Bitmap ready=bitmap;
                runOnUiThread(()->{if(!closed&&token==generation){image.setImageBitmap(ready);}else ready.recycle();});
            } catch(Exception e) {
                if(bitmap!=null)bitmap.recycle();
                runOnUiThread(()->{if(!closed&&token==generation)status.setText(R.string.jag_manual_error);});
            } finally {
                if(temporary!=null)temporary.delete();
            }
        });
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("page",page);out.putInt("width",width);}
    @Override protected void onDestroy(){closed=true;generation++;worker.shutdownNow();image.setImageDrawable(null);super.onDestroy();}
}
