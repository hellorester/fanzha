package cn.course.antifraud;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.event.IIOReadProgressListener;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.*;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipInputStream;
import static cn.course.antifraud.Support.*;

/** Bounded validation for local evidence uploads; no extraction or external scanning. */
final class AttachmentContent {
    private static final Semaphore IMAGE_READERS=new Semaphore(2);
    private AttachmentContent(){}
    static void image(byte[] data,String extension){
        if(!IMAGE_READERS.tryAcquire())throw new Problem(429,"正在处理其他图片，请稍后重试");
        ImageReader reader=null;
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(data))){
            var readers=ImageIO.getImageReaders(input);require(readers.hasNext(),"图片内容与格式不匹配");reader=readers.next();reader.setInput(input,true,true);
            String format=reader.getFormatName();require(extension.equals("png")?format.equalsIgnoreCase("png"):format.equalsIgnoreCase("jpeg"),"图片内容与扩展名不匹配");
            int width=reader.getWidth(0),height=reader.getHeight(0);
            require(width>0&&height>0&&width<=10000&&height<=10000&&(long)width*height<=20_000_000,"图片尺寸过大，最多2000万像素且单边不超过10000像素");
            long started=System.nanoTime();boolean[] aborted={false};
            reader.addIIOReadProgressListener(new IIOReadProgressListener(){
                public void imageProgress(ImageReader source,float percent){if(System.nanoTime()-started>3_000_000_000L){aborted[0]=true;source.abort();}}
                public void sequenceStarted(ImageReader r,int index){} public void sequenceComplete(ImageReader r){}
                public void imageStarted(ImageReader r,int index){} public void imageComplete(ImageReader r){}
                public void thumbnailStarted(ImageReader r,int image,int thumbnail){} public void thumbnailProgress(ImageReader r,float percent){}
                public void thumbnailComplete(ImageReader r){} public void readAborted(ImageReader r){aborted[0]=true;}
            });
            var decoded=reader.read(0);
            if(decoded!=null)decoded.flush();require(!aborted[0]&&decoded!=null,"图片解码未完成，请缩小图片后重试");
        }catch(IOException|IllegalArgumentException e){throw new Problem(400,"图片内容损坏或格式无效");}
        finally{if(reader!=null)reader.dispose();IMAGE_READERS.release();}
    }
    static void docx(byte[] data){
        long total=0,started=System.nanoTime();int entries=0;boolean document=false,types=false;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(data))){
            java.util.zip.ZipEntry entry;byte[] buffer=new byte[8192];
            while((entry=zip.getNextEntry())!=null){
                require(++entries<=1000,"Word文件内部条目过多");long size=0;int read;
                while((read=zip.read(buffer))!=-1){size+=read;total+=read;require(size<=20L*1024*1024&&total<=50L*1024*1024,"Word文件解压后的内容过大");require(System.nanoTime()-started<=3_000_000_000L,"Word文件处理超时，请简化材料后重试");}
                if(!entry.isDirectory()){document|=entry.getName().equals("word/document.xml");types|=entry.getName().equals("[Content_Types].xml");}
            }
            require(document&&types,"Word文件格式无效");
        }catch(IOException|IllegalArgumentException e){throw new Problem(400,"Word文件内容损坏或格式无效");}
    }
}
