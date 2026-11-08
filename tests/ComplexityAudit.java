import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;

/** AST-based decision count: 1 + if/loop/catch/ternary/non-default-case/&&/||. */
public class ComplexityAudit {
 public static void main(String[] args)throws Exception{
  Path root=Path.of(args[0]);List<File> sources;try(var stream=Files.walk(root)){sources=stream.filter(p->p.toString().endsWith(".java")).map(Path::toFile).toList();}
  JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();var manager=compiler.getStandardFileManager(null,null,java.nio.charset.StandardCharsets.UTF_8);
  var task=(JavacTask)compiler.getTask(null,manager,null,List.of("-proc:none"),null,manager.getJavaFileObjectsFromFiles(sources));
  System.out.println("file,method,cyclomatic_decisions_plus_one");
  for(var unit:task.parse())new TreeScanner<Void,Void>(){
   public Void visitMethod(MethodTree method,Void ignored){
    if(method.getBody()==null)return null;
    final int[] score={1};new TreeScanner<Void,Void>(){
     public Void scan(Tree node,Void v){if(node!=null){switch(node.getKind()){
      case IF,FOR_LOOP,ENHANCED_FOR_LOOP,WHILE_LOOP,DO_WHILE_LOOP,CATCH,CONDITIONAL_EXPRESSION,CONDITIONAL_AND,CONDITIONAL_OR->score[0]++;
      case CASE->{if(!((CaseTree)node).getExpressions().isEmpty())score[0]++;}
      default->{}
     }}return super.scan(node,v);}
    }.scan(method.getBody(),null);
    System.out.println(Path.of(unit.getSourceFile().toUri()).getFileName()+","+method.getName()+","+score[0]);return null;
   }
  }.scan(unit,null);
  manager.close();
 }
}
