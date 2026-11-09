package cn.course.antifraud;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.core.io.FileSystemResource;
import java.util.*;
import static cn.course.antifraud.Support.*;

@RestController
@RequestMapping("/api")
public class Api {
    private final Support s;private final Auth auth;private final Clues clues;private final Files files;private final QueryLimiter limiter;
    public Api(Support s,Auth auth,Clues clues,Files files,QueryLimiter limiter){this.s=s;this.auth=auth;this.clues=clues;this.files=files;this.limiter=limiter;}
    @GetMapping("/session") public Object session(HttpServletRequest req){return map("user",auth.optional(req),"csrf",auth.token(req.getSession()),"now",s.now(),"mode","课程模拟演示");}
    @PostMapping("/login") public Object login(HttpServletRequest req,@RequestBody Map<String,Object> b){return auth.login(req,b);}
    @PostMapping("/logout") public Object logout(HttpServletRequest req){req.getSession().invalidate();return map("ok",true);}
    @PostMapping("/attachments") public Object upload(HttpServletRequest req,@RequestParam("file") MultipartFile file)throws Exception{return files.upload(file,auth.optional(req),auth.owner(req));}
    @DeleteMapping("/attachments/{id}") public Object deleteAttachment(HttpServletRequest req,@PathVariable String id){files.deleteDraft(id,auth.optional(req),auth.owner(req));return map("ok",true);}
    @GetMapping("/attachments/{id}") public ResponseEntity<?> download(HttpServletRequest req,@PathVariable String id){var f=files.readable(id,auth.optional(req),auth.owner(req));return ResponseEntity.ok().contentType(MediaType.parseMediaType(str(f,"mime"))).header("Content-Disposition",ContentDisposition.attachment().filename(str(f,"filename"),java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(new FileSystemResource(files.path(id)));}
    @GetMapping("/reports") public Object reports(HttpServletRequest req){return clues.reports(auth.user(req));}
    @GetMapping("/reports/{id}") public Object report(HttpServletRequest req,@PathVariable long id){return clues.report(auth.user(req),id);}
    @PostMapping("/reports") public Object report(HttpServletRequest req,@RequestBody Map<String,Object> b){var result=clues.submit(auth.optional(req),auth.owner(req),b);if(!yes(result,"duplicate"))try{clues.process(num(result,"id"));}catch(Exception e){clues.failure(num(result,"id"));result.put("processing","人工待研判");}return result;}
    @PostMapping("/reports/{id}/retry") public Object retry(HttpServletRequest req,@PathVariable long id){auth.user(req).roles("CENTER");clues.process(id);return map("ok",true);}
    @PostMapping("/anonymous/query") public Object anonymous(HttpServletRequest req,@RequestBody Map<String,Object> b){limiter.consume(req.getRemoteAddr());return clues.anonymous(b);}
    @PostMapping("/reports/{id}/query-token") public Object queryToken(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object> b){return clues.queryToken(auth.user(req),id,b);}
    @GetMapping("/alerts") public Object alerts(HttpServletRequest req){return clues.alerts(auth.user(req));}
    @GetMapping("/alerts/{id}") public Object alert(HttpServletRequest req,@PathVariable long id){return clues.alert(auth.user(req),id);}
    @PostMapping("/alerts/{id}/review") public Object review(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object> b){clues.review(auth.user(req),id,b);return map("ok",true);}
    @PostMapping("/alerts/{id}/dispatch") public Object dispatch(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object> b){return clues.dispatch(auth.user(req),id,b);}
    @PostMapping("/alerts/{id}/close") public Object close(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object> b){clues.close(auth.user(req),id,b);return map("ok",true);}
    @PostMapping("/alerts/{id}/void") public Object voidAlert(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object> b){clues.voidAlert(auth.user(req),id,b);return map("ok",true);}
}
