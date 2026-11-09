package cn.course.antifraud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentContentTest {
    private byte[] picture(String format)throws IOException{
        var output=new ByteArrayOutputStream();var image=new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image,format,output);image.flush();return output.toByteArray();
    }
    @ParameterizedTest @ValueSource(strings={"png","jpg","jpeg"})
    void acceptsSmallRealImages(String format)throws Exception{assertDoesNotThrow(()->AttachmentContent.image(picture(format),format));}
    @Test void rejectsMismatchedExtension()throws Exception{
        var png=picture("png");assertEquals(400,assertThrows(Support.Problem.class,()->AttachmentContent.image(png,"jpg")).status);
    }
    @Test void rejectsHugeDimensionsBeforeReadingImagePixels()throws Exception{
        byte[] png=picture("png"); // Change IHDR dimensions and its CRC, keeping the tiny compressed body.
        var header=java.nio.ByteBuffer.wrap(png);header.putInt(16,10000);header.putInt(20,10000);
        var crc=new CRC32();crc.update(png,12,17);header.putInt(29,(int)crc.getValue());
        var error=assertThrows(Support.Problem.class,()->AttachmentContent.image(png,"png"));
        assertTrue(error.getMessage().contains("尺寸过大"));
    }
    @Test void rejectsCorruptImage(){assertThrows(Support.Problem.class,()->AttachmentContent.image(new byte[]{1,2,3},"png"));}
    private byte[] document(int entries,int expandedMegabytes,boolean bombFirst,boolean types)throws IOException{
        var output=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(output)){
            if(bombFirst)entry(zip,"extra.bin",expandedMegabytes);
            if(types){zip.putNextEntry(new ZipEntry("[Content_Types].xml"));zip.write("<Types/>".getBytes());zip.closeEntry();}
            zip.putNextEntry(new ZipEntry("word/document.xml"));zip.write("<document/>".getBytes());zip.closeEntry();
            if(!bombFirst)entry(zip,"extra.bin",expandedMegabytes);
            for(int i=0;i<entries;i++){zip.putNextEntry(new ZipEntry("empty-"+i));zip.closeEntry();}
        }return output.toByteArray();
    }
    private void entry(ZipOutputStream zip,String name,int megabytes)throws IOException{
        zip.putNextEntry(new ZipEntry(name));var block=new byte[1024*1024];for(int i=0;i<megabytes;i++)zip.write(block);zip.closeEntry();
    }
    @Test void acceptsSmallDocx()throws Exception{assertDoesNotThrow(()->AttachmentContent.docx(document(0,0,false,true)));}
    @ParameterizedTest @ValueSource(booleans={true,false})
    void boundsExpansionBeforeAndAfterDocumentEntry(boolean first)throws Exception{
        byte[] bomb=document(0,21,first,true);assertTrue(bomb.length<100000);
        assertTrue(assertThrows(Support.Problem.class,()->AttachmentContent.docx(bomb)).getMessage().contains("内容过大"));
    }
    @Test void boundsNumberOfEntries()throws Exception{
        byte[] many=document(1001,0,false,true);assertTrue(assertThrows(Support.Problem.class,()->AttachmentContent.docx(many)).getMessage().contains("条目过多"));
    }
    @Test void requiresContentTypes()throws Exception{var incomplete=document(0,0,false,false);assertThrows(Support.Problem.class,()->AttachmentContent.docx(incomplete));}
    @Test void rejectsNonZip(){assertThrows(Support.Problem.class,()->AttachmentContent.docx("not a zip".getBytes()));}
    @Test void boundsTotalExpandedSize()throws Exception{
        var output=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(output)){for(int i=0;i<3;i++)entry(zip,"part-"+i,18);}
        assertTrue(assertThrows(Support.Problem.class,()->AttachmentContent.docx(output.toByteArray())).getMessage().contains("内容过大"));
    }
}
