package com.blessrom.commerce;

import java.time.*;
import java.util.*;

final class QueryFilters {
    private QueryFilters() {}
    static final ZoneId LIMA=ZoneId.of("America/Lima");
    static LocalDate date(String input) {
        if(input==null||input.isBlank())return null;
        try{return LocalDate.parse(input);}
        catch(java.time.format.DateTimeParseException e){throw new Problem(400,"INVALID_DATE","Usa una fecha AAAA-MM-DD.");}
    }
    static void statuses(StringBuilder sql,List<Object> args,String column,String input,Set<String> allowed) {
        if(input==null||input.isBlank())return;
        if(input.length()>300)throw new Problem(400,"INVALID_STATUS","Filtro de estado no válido.");
        var values=new LinkedHashSet<String>();
        for(String value:input.split(",",-1)) {
            String status=value.trim();
            if(!allowed.contains(status))throw new Problem(400,"INVALID_STATUS","Filtro de estado no válido.");
            values.add(status);
        }
        sql.append(" AND ").append(column).append(" IN (").append(String.join(",",Collections.nCopies(values.size(),"?"))).append(')');args.addAll(values);
    }
    static void dates(StringBuilder sql,List<Object> args,String column,LocalDate from,LocalDate to) {
        if(from!=null&&to!=null&&to.isBefore(from))throw new Problem(400,"INVALID_DATE_RANGE","El período no es válido.");
        if(from!=null){sql.append(" AND ").append(column).append(">=?");args.add(from.atStartOfDay(LIMA).toInstant());}
        if(to!=null){sql.append(" AND ").append(column).append("<?");args.add(to.plusDays(1).atStartOfDay(LIMA).toInstant());}
    }
}
