import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.media.ImageReader;
import android.os.Looper;
import java.io.*;
import java.util.*;

/** Exact shader AND mesh fixture. Offscreen blocking draw/readback time is not isolated GPU cost or app FPS. */
public final class NeonLoFiAndroidProbe {
    static final class Mesh {
        float[] positions; int[] colors; short[] indices;
        Mesh(DataInputStream input) throws IOException {
            int vertices = input.readInt(), count = input.readInt();
            if (vertices < 0 || vertices > 32767 || count < 0 || count > 30000) throw new IOException("Invalid mesh bounds");
            positions = new float[vertices * 2]; colors = new int[vertices]; indices = new short[count];
            for (int i=0;i<positions.length;i++) positions[i]=input.readFloat();
            for (int i=0;i<colors.length;i++) colors[i]=input.readInt();
            for (int i=0;i<indices.length;i++) indices[i]=input.readShort();
        }
        void draw(Canvas canvas, Paint paint) {
            if (indices.length > 0) canvas.drawVertices(Canvas.VertexMode.TRIANGLES, positions.length, positions, 0,
                null, 0, colors, 0, indices, 0, indices.length, paint);
        }
    }
    public static void main(String[] args) {
        try { run(args); System.exit(0); } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
    }
    static void run(String[] args) throws Exception {
        Looper.prepareMainLooper();
        String folder=args[0]; int width=Integer.parseInt(args[1]), height=Integer.parseInt(args[2]), frames=Integer.parseInt(args[3]);
        if (width<32 || height<32 || width>3840 || height>3840 || frames<1 || frames>120) throw new IllegalArgumentException("Invalid probe bounds");
        OdysseyAndroidProbe.WIDTH=width; OdysseyAndroidProbe.HEIGHT=height;
        RuntimeShader shader=OdysseyAndroidProbe.read(folder,"neonlofi");
        ImageReader reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT|HardwareBuffer.USAGE_CPU_READ_OFTEN);
        HardwareRenderer renderer=new HardwareRenderer(); RenderNode node=new RenderNode("Neon Lo-Fi offscreen fixture");
        node.setPosition(0,0,width,height); renderer.setSurface(reader.getSurface()); renderer.setContentRoot(node);
        try {
            for(int mode=1;mode<=4;mode++) {
                Properties properties=new Properties();
                try(InputStream in=new FileInputStream(folder+"/neon-"+mode+".properties")) { properties.load(in); }
                for(String name:properties.stringPropertyNames()) {
                    String[] parts=properties.getProperty(name).split(","); float[] values=new float[parts.length];
                    for(int i=0;i<parts.length;i++) values[i]=Float.parseFloat(parts[i]);
                    OdysseyAndroidProbe.set(shader,name,values);
                }
                float sourceWidth=Float.parseFloat(properties.getProperty("uResolution").split(",")[0]);
                float sourceHeight=Float.parseFloat(properties.getProperty("uResolution").split(",")[1]);
                // Preserve the actual fixture's projection when measuring larger same-aspect surfaces.
                if(Math.abs(width/(float)height-sourceWidth/sourceHeight)>.001f)
                    throw new IllegalArgumentException("Export a reference at the requested aspect ratio first");
                OdysseyAndroidProbe.set(shader,"uResolution",width,height);
                Bitmap ridge=BitmapFactory.decodeFile(folder+"/neon-"+mode+"-ridges.png");
                Bitmap bands=BitmapFactory.decodeFile(folder+"/neon-"+mode+"-bands.png");
                if(ridge==null || bands==null) throw new AssertionError("Missing fixture textures");
                BitmapShader ridgeShader=new BitmapShader(ridge,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
                BitmapShader bandShader=new BitmapShader(bands,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
                ridgeShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR); bandShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                shader.setInputShader("nRidges",ridgeShader); shader.setInputShader("uBandsTex",bandShader);
                for(String name:new String[]{"uScopeTex","uPaletteTex","uHistoryTex"}) if(OdysseyAndroidProbe.declares(shader,name)) shader.setInputShader(name,bandShader);
                Mesh[] meshes;
                try(DataInputStream input=new DataInputStream(new FileInputStream(folder+"/neon-"+mode+"-mesh.bin"))) {
                    // Sky, city, floor and front dressing, then the lamps' light added on top.
                    int count=input.readInt(); if(count!=5) throw new IOException("Expected five mesh submissions");
                    meshes=new Mesh[count]; for(int i=0;i<count;i++) meshes[i]=new Mesh(input);
                }
                double[] cost=new double[frames]; int[] last=null;
                Paint background=new Paint(); background.setShader(shader); Paint meshPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
                Paint glowPaint=new Paint(Paint.ANTI_ALIAS_FLAG); glowPaint.setBlendMode(BlendMode.PLUS);
                for(int f=-5;f<frames;f++) {
                    long before=System.nanoTime(); RecordingCanvas canvas=node.beginRecording(width,height);
                    canvas.drawRect(0,0,width,height,background);
                    canvas.save(); canvas.scale(width/sourceWidth,height/sourceHeight);
                    for(int m=0;m<meshes.length;m++) meshes[m].draw(canvas,m==4?glowPaint:meshPaint); canvas.restore(); node.endRecording();
                    int sync=renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
                    try(android.media.Image image=reader.acquireNextImage()) {
                        if(image==null) throw new AssertionError("No GPU image, sync="+sync);
                        image.getPlanes()[0].getBuffer().get(0);
                        if(f>=0) cost[f]=(System.nanoTime()-before)/1e6;
                        if(f==frames-1) last=OdysseyAndroidProbe.pixels(image);
                    }
                }
                Bitmap result=Bitmap.createBitmap(last,width,height,Bitmap.Config.ARGB_8888);
                try(OutputStream out=new FileOutputStream(folder+"/neon-gpu-"+mode+".png")) { result.compress(Bitmap.CompressFormat.PNG,100,out); }
                Bitmap reference=BitmapFactory.decodeFile(folder+"/neon-"+mode+"-reference.png");
                if(reference!=null && reference.getWidth()==width && reference.getHeight()==height) {
                    int[] expected=new int[width*height]; reference.getPixels(expected,0,width,0,0,width,height);
                    long delta=0,signal=0;
                    for(int i=0;i<last.length;i++) for(int c=0;c<3;c++) {
                        int e=(expected[i]>>>(c*8))&255; signal+=e; delta+=Math.abs(e-((last[i]>>>(c*8))&255));
                    }
                    double error=delta/(double)Math.max(signal,1);
                    if(error>.15) throw new AssertionError("Desktop reference mismatch: "+error);
                    System.out.printf("PASS scene %d full shader/mesh reference error %.4f%n",mode,error);
                } else System.out.println("Reference pixel comparison skipped: dimensions differ");
                Arrays.sort(cost);
                System.out.printf(Locale.US,"scene=%d blocking_offscreen_draw_readback_p50_ms=%.3f p95_ms=%.3f%n",mode,cost[frames/2],cost[Math.min(frames-1,(int)(frames*.95))]);
                if(reference!=null) reference.recycle(); result.recycle(); ridge.recycle(); bands.recycle();
            }
        } finally { renderer.destroy(); reader.close(); }
    }
}
