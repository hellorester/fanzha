package cn.course.antifraud;

import java.math.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import static cn.course.antifraud.Support.*;

public final class Rules {
    private Rules(){}
    public static Map<String,Object> defaults(){return map("weights",List.of(25,15,15,30,15),"high",70,"medium",40,"mergeHours",24,"freezeHours",48,"extensionHours",48,"maxExtensions",2,"timeoutMinutes",30);}
    public static String normalize(String kind,String raw){
        String v=raw.strip();require(!v.isEmpty()&&v.length()<=500,"线索不能为空或过长");
        switch(kind){
            case "号码" -> {v=v.replaceAll("[\\s()（）-]","");if(v.startsWith("+86"))v=v.substring(3);else if(v.startsWith("0086"))v=v.substring(4);require(v.matches("[0-9]{7,15}"),"号码格式不正确");return v;}
            case "账户" -> {v=v.replaceAll("[\\s-]","");require(v.matches("[0-9]{6,32}"),"账户应为6至32位数字");return v;}
            case "网址" -> {try{URI uri=URI.create(v.contains("://")?v:"https://"+v);require(Set.of("http","https").contains(uri.getScheme().toLowerCase())&&uri.getHost()!=null&&uri.getUserInfo()==null,"网址格式不正确");String path=Objects.toString(uri.getRawPath(),"").replaceAll("/+$","");return uri.getHost().toLowerCase(Locale.ROOT)+(uri.getPort()==-1?"":":"+uri.getPort())+path;}catch(IllegalArgumentException e){throw new Problem(400,"网址格式不正确");}}
            default -> throw new Problem(400,"请选择号码、账户或网址");
        }
    }
    @SuppressWarnings("unchecked") public static void validateConfig(Map<String,Object> c){
        require(c.get("weights") instanceof List<?>,"权重必须为五项整数");List<?> w=(List<?>)c.get("weights");require(w.size()==5,"权重必须恰好五项");int sum=0;for(Object x:w){BigDecimal d=money(x);require(d.scale()==2&&d.stripTrailingZeros().scale()<=0&&d.intValue()>0,"权重必须为正整数");sum+=d.intValue();}require(sum==100,"权重之和必须为100");
        require(num(c,"medium")>=1&&num(c,"high")<=100&&num(c,"high")>num(c,"medium"),"高风险阈值须大于中风险阈值，且在1至100内");
        for(String k:List.of("mergeHours","freezeHours","extensionHours","timeoutMinutes"))require(num(c,k)>0&&num(c,k)<=1680,"时限必须为1至1680的正整数");
        require(num(c,"maxExtensions")>=0&&num(c,"maxExtensions")<=10,"延期上限须为0至10");
    }
    public static Map<String,Object> score(Map<String,Object> input,boolean hit,Map<String,Object> config){
        List<Integer> weights=new ArrayList<>();boolean fallback=false;
        try{List<?> raw=(List<?>)config.get("weights");if(raw.size()!=5)throw new IllegalArgumentException();int sum=0;for(Object x:raw){int a=Integer.parseInt(x.toString());if(a<=0)throw new IllegalArgumentException();weights.add(a);sum+=a;}if(sum!=100)throw new IllegalArgumentException();}catch(Exception e){weights=List.of(25,15,15,30,15);fallback=true;}
        int amountScore=0,timeScore=0;
        if(!str(input,"amount").isEmpty()){BigDecimal a=amount(input,"amount");require(a.signum()>=0,"金额不能为负数");amountScore=a.compareTo(new BigDecimal("1000"))<0?20:a.compareTo(new BigDecimal("5000"))<0?50:a.compareTo(new BigDecimal("20000"))<0?75:100;}
        if(!str(input,"transactionTime").isEmpty()){LocalTime t;try{t=LocalTime.parse(str(input,"transactionTime"));}catch(Exception e){throw new Problem(400,"交易时间格式不正确");}timeScore=t.isBefore(LocalTime.of(5,0))||!t.isBefore(LocalTime.of(23,0))?100:30;}
        int abnormal=yes(input,"newLarge")?100:yes(input,"repeat")?80:0;
        List<Integer> factors=List.of(amountScore,timeScore,yes(input,"remote")?100:0,hit?100:0,abnormal);
        List<String> names=List.of("交易金额","交易时段","异地交易","黑名单","异常行为");List<Map<String,Object>> details=new ArrayList<>();BigDecimal total=BigDecimal.ZERO;
        for(int i=0;i<5;i++){BigDecimal weighted=BigDecimal.valueOf((long)weights.get(i)*factors.get(i)).divide(BigDecimal.valueOf(100));total=total.add(weighted);details.add(map("name",names.get(i),"factor",factors.get(i),"weight",weights.get(i),"contribution",weighted));}
        int score=Math.clamp(total.setScale(0,RoundingMode.HALF_UP).intValue(),0,100);long high=num(config,"high"),medium=num(config,"medium");if(high<=medium||high>100||medium<1){high=70;medium=40;fallback=true;}
        return map("score",score,"level",score>=high?"高":score>=medium?"中":"低","details",details,"fallback",fallback,"hit",hit);
    }
}
