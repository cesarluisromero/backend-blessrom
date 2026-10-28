package com.blessrom.commerce;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.sql.*;
import java.time.*;
import java.util.*;
/** JDBC connections enlist in the enclosing service JTA transaction. */
@ApplicationScoped public class Db {
    @Inject AgroalDataSource source;
    private static final Logger LOG=Logger.getLogger(Db.class);
    public List<Map<String,Object>> rows(String sql,Object...args) {
        try(var c=source.getConnection();var s=c.prepareStatement(sql)) {
            bind(s,args);
            try(var r=s.executeQuery()) {
                var list=new ArrayList<Map<String,Object>>();var meta=r.getMetaData();
                while(r.next()) {
                    var row=new LinkedHashMap<String,Object>();
                    for(int i=1;i<=meta.getColumnCount();i++) {
                        Object value=r.getObject(i);
                        if(value instanceof Timestamp t) value=t.toInstant().toString();
                        if(value instanceof OffsetDateTime t) value=t.toInstant().toString();
                        row.put(meta.getColumnLabel(i),value);
                    }
                    list.add(row);
                }
                return list;
            }
        } catch(SQLException e) { throw translate(e); }
    }
    public Map<String,Object> one(String sql,Object...args) { var rows=rows(sql,args);if(rows.isEmpty()) throw Problem.missing();return rows.getFirst(); }
    public int execute(String sql,Object...args) {
        try(var c=source.getConnection();var s=c.prepareStatement(sql)) { bind(s,args);return s.executeUpdate(); }
        catch(SQLException e) { throw translate(e); }
    }
    private void bind(PreparedStatement s,Object[] args) throws SQLException {
        for(int i=0;i<args.length;i++) {
            Object value=args[i];
            if(value instanceof Instant t) value=OffsetDateTime.ofInstant(t,ZoneOffset.UTC);
            s.setObject(i+1,value);
        }
    }
    private RuntimeException translate(SQLException e) {
        String state=e.getSQLState();
        if(state!=null&&(state.startsWith("23")||state.startsWith("40")))
            return Problem.conflict("DATA_CONFLICT","La operación entra en conflicto con los datos actuales.");
        LOG.error("Error de persistencia",e);
        return new Problem(500,"DATABASE_ERROR","No se pudo completar la operación.");
    }
    static UUID id(Map<String,Object> row,String key) { return (UUID)row.get(key); }
    static int integer(Map<String,Object> row,String key) { return ((Number)row.get(key)).intValue(); }
    static long version(Map<String,Object> row) { return ((Number)row.get("version")).longValue(); }
}

