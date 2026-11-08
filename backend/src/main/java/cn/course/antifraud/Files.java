package cn.course.antifraud;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import static cn.course.antifraud.Support.*;

@Service
public class Files {
    private final Support s;private final Path root;
    public Files(Support s,@Value("${app.storage}") String directory)throws IOException{this.s=s;root=Path.of(directory).toAbsolutePath().normalize();java.nio.file.Files.createDirectories(root);}
    public Path path(String id){require(id.matches("[a-f0-9-]{36}"),"附件编号无效");Path p=root.resolve(id).normalize();require(p.startsWith(root),"附件路径无效");return p;}
    @Transactional public Map<String,Object> upload(MultipartFile file,User u,String owner)throws IOException{
        require(!file.isEmpty()&&file.getSize()<=10*1024*1024,"附件不能为空或超过10MB");String name=Path.of(Objects.toString(file.getOriginalFilename(),"附件").replace('\\','/')).getFileName().toString();require(name.length()<=180,"文件名过长");String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);byte[] data=file.getBytes();String mime;
        switch(ext){
            case "png","jpg","jpeg" -> {require(javax.imageio.ImageIO.read(new ByteArrayInputStream(data))!=null,"图片内容与格式不匹配");mime=ext.equals("png")?"image/png":"image/jpeg";}
            case "pdf" -> {require(data.length>5&&new String(data,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"),"PDF文件格式无效");mime="application/pdf";}
            case "docx" -> {boolean valid=false;try(var zip=new java.util.zip.ZipInputStream(new ByteArrayInputStream(data))){java.util.zip.ZipEntry e;int entries=0;while((e=zip.getNextEntry())!=null&&entries++<1000){if(e.getName().equals("word/document.xml")){valid=true;break;}}}require(valid,"Word文件格式无效");mime="application/vnd.openxmlformats-officedocument.wordprocessingml.document";}
            case "txt" -> {require(new String(data,java.nio.charset.StandardCharsets.UTF_8).indexOf('\0')<0,"文本格式无效");mime="text/plain";}
            default -> throw new Problem(400,"支持PNG、JPG、PDF、DOCX、TXT文件");
        }
        String id=UUID.randomUUID().toString();Path target=path(id);java.nio.file.Files.write(target,data,StandardOpenOption.CREATE_NEW);
        try{s.db.update("INSERT INTO af_attachment(id,owner_key,user_id,filename,mime,bytes,created_at) VALUES(?,?,?,?,?,?,?)",id,hash(owner),u==null?null:u.id(),name,mime,data.length,s.now());}catch(RuntimeException e){java.nio.file.Files.deleteIfExists(target);throw e;}
        return map("id",id,"filename",name,"bytes",data.length);
    }
    public void bind(Object raw,User u,String owner,String type,long id,boolean mandatory){
        List<?> ids=raw instanceof List<?> list?list:List.of();require(ids.size()<=10,"一次最多10个附件");if(mandatory)require(!ids.isEmpty(),"请上传证明或现场材料");
        for(Object fileId:ids){Map<String,Object> f=s.one("SELECT * FROM af_attachment WHERE id=? FOR UPDATE",fileId);require(str(f,"owner_key").equals(hash(owner))||u!=null&&num(f,"user_id")==u.id(),"无法关联其他人的附件");conflict(f.get("entity_id")==null||str(f,"entity_type").equals(type)&&num(f,"entity_id")==id,"附件已关联其他单据");s.db.update("UPDATE af_attachment SET entity_type=?,entity_id=? WHERE id=?",type,id,fileId);}
    }
    public List<Map<String,Object>> list(String type,long id){return s.rows("SELECT id,filename,mime,bytes FROM af_attachment WHERE entity_type=? AND entity_id=?",type,id);}
    public Map<String,Object> readable(String id,User u,String owner){Map<String,Object> f=s.one("SELECT * FROM af_attachment WHERE id=?",id);boolean allowed=f.get("entity_id")==null&&str(f,"owner_key").equals(hash(owner));if(u!=null){allowed|=u.role().equals("CENTER");String t=str(f,"entity_type");long e=num(f,"entity_id");if(t.equals("report")){allowed|=u.role().equals("PUBLIC")&&s.count("SELECT COUNT(*) FROM af_report WHERE id=? AND (owner_id=? OR victim_id=?)",e,u.id(),u.victimId()==0?-1:u.victimId())>0;if(u.role().equals("PAYMENT"))allowed|=s.count("SELECT COUNT(*) FROM af_command c JOIN af_report r ON r.alert_id=c.alert_id WHERE r.id=? AND c.org_id=?",e,u.orgId())>0;if(u.role().equals("GRID"))allowed|=s.count("SELECT COUNT(*) FROM af_visit v JOIN af_report r ON r.alert_id=v.alert_id WHERE r.id=? AND v.community_id=?",e,u.communityId())>0;}else if(t.equals("visit")&&u.role().equals("GRID"))allowed|=s.count("SELECT COUNT(*) FROM af_visit WHERE id=? AND community_id=?",e,u.communityId())>0;else if(t.equals("receipt")&&u.role().equals("PAYMENT"))allowed|=s.count("SELECT COUNT(*) FROM af_receipt r JOIN af_command c ON r.command_id=c.id WHERE r.id=? AND c.org_id=?",e,u.orgId())>0;else if(t.equals("application")){allowed|=u.role().equals("PUBLIC")&&s.count("SELECT COUNT(*) FROM af_application WHERE id=? AND owner_id=?",e,u.id())>0;if(u.role().equals("PAYMENT"))allowed|=s.count("SELECT COUNT(*) FROM af_command WHERE application_id=? AND org_id=?",e,u.orgId())>0;}}
        if(!allowed)throw new Problem(404,"附件不存在或无权访问");return f;
    }
}
