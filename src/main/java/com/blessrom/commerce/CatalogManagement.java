package com.blessrom.commerce;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

@ApplicationScoped @Transactional
public class CatalogManagement {
 @Inject Db db; @Inject Json json; @Inject CatalogService catalog;
 public record CategoryEdit(@Min(0) long version,@NotNull @Valid Api.Category category,Boolean selectable){}
 public record CollectionEdit(@Min(0) long version,@NotNull @Valid Api.Collection collection,Boolean selectable){}
 public record OptionEdit(@Min(0) long version,@NotNull @Valid Api.AttributeOption option,Boolean selectable){}
 private String table(String kind){return switch(kind){case "category"->"categories";case "collection"->"collections";case "option"->"attribute_options";default->throw Problem.missing();};}
 public Map<String,Object> page(String kind,UUID attribute,String q,int page,int size){
  CatalogService.validatePage(page,size);String table=table(kind),name="option".equals(kind)?"label":"name";
  var args=new ArrayList<Object>();String where=" WHERE true";
  if(attribute!=null){where+=" AND attribute_id=?";args.add(attribute);}
  if(q!=null&&!q.isBlank()){where+=" AND ("+name+" ILIKE ? OR "+("option".equals(kind)?"value":"slug")+" ILIKE ?)";args.add("%"+q.trim()+"%");args.add("%"+q.trim()+"%");}
  Object total=db.one("SELECT count(*) AS n FROM "+table+where,args.toArray()).get("n");args.add(size);args.add(page*size);
  return Map.of("items",db.rows("SELECT * FROM "+table+where+" ORDER BY display_order,lower("+name+"),id LIMIT ? OFFSET ?",args.toArray()),"page",page,"size",size,"total",total);
 }
 public Map<String,Object> detail(String kind,UUID id){var result=db.one("SELECT * FROM "+table(kind)+" WHERE id=?",id);result.put("usage",usage(kind,id));return result;}
 public List<Map<String,Object>> usage(String kind,UUID id){
  String condition;Object target=id.toString();var args=new ArrayList<Object>();
  if("option".equals(kind)){
   var option=db.one("SELECT o.value,d.code FROM attribute_options o JOIN attribute_definitions d ON d.id=o.attribute_id WHERE o.id=?",id);
   condition="EXISTS(SELECT 1 FROM jsonb_array_elements(COALESCE(content->'variants','[]'::jsonb)) v WHERE v->'attributes'->>?=?)";args.add(option.get("code"));args.add(option.get("value"));
  }else{condition="category".equals(kind)?"content->>'categoryId'=?":"COALESCE(content->'collectionIds','[]'::jsonb) @> jsonb_build_array(?::text)";args.add(target);}
  var rows=new ArrayList<>(db.rows("SELECT DISTINCT p.id,COALESCE(c.content->>'name',p.slug) AS label,'product' AS type FROM products p JOIN (SELECT product_id,content FROM product_drafts UNION ALL SELECT product_id,content FROM product_revisions) c ON c.product_id=p.id WHERE "+condition,args.toArray()));
  if("category".equals(kind))rows.addAll(db.rows("SELECT id,name AS label,'category' AS type FROM categories WHERE parent_id=?",id));
  if(!"option".equals(kind)){
   rows.addAll(db.rows("SELECT id,'Revisión de portada' AS label,'home' AS type FROM storefront_revisions WHERE content::text LIKE ?","%"+id+"%"));
   if(!db.rows("SELECT page_key FROM storefront_pages WHERE draft_content::text LIKE ?","%"+id+"%").isEmpty())rows.add(Map.of("id","home","label","Borrador de portada","type","home"));
  }
  return rows;
 }
 public Object updateCategory(UUID id,CategoryEdit input){
  var current=db.one("SELECT * FROM categories WHERE id=? FOR UPDATE",id);Problem.version(Db.version(current),input.version());var v=input.category();
  if(v.parentId()!=null){if(id.equals(v.parentId())||!db.rows("WITH RECURSIVE children AS (SELECT id FROM categories WHERE parent_id=? UNION ALL SELECT c.id FROM categories c JOIN children p ON c.parent_id=p.id) SELECT id FROM children WHERE id=?",id,v.parentId()).isEmpty())throw new Problem(400,"CATEGORY_CYCLE","La categoría superior produciría un ciclo.");db.one("SELECT id FROM categories WHERE id=?",v.parentId());}
  db.execute("UPDATE categories SET name=?,slug=?,description=?,parent_id=?,display_order=?,active=?,selectable=?,version=version+1 WHERE id=?",v.name(),v.slug(),Objects.requireNonNullElse(v.description(),""),v.parentId(),Objects.requireNonNullElse(v.displayOrder(),0),Objects.requireNonNullElse(v.active(),true),input.selectable()==null?current.get("selectable"):input.selectable(),id);return detail("category",id);
 }
 public Object updateCollection(UUID id,CollectionEdit input){
  catalog.updateCollection(id,new Api.UpdateCollection(input.version(),input.collection()));
  if(input.selectable()!=null)db.execute("UPDATE collections SET selectable=? WHERE id=?",input.selectable(),id);
  return detail("collection",id);
 }
 public Object updateOption(UUID id,OptionEdit input){
  var current=db.one("SELECT * FROM attribute_options WHERE id=? FOR UPDATE",id);Problem.version(Db.version(current),input.version());var v=input.option();
  if(!Objects.equals(v.value(),current.get("value"))&&!usage("option",id).isEmpty())throw Problem.conflict("OPTION_VALUE_IN_USE","El valor interno está en uso. Puedes modificar etiqueta, color y disponibilidad.");
  db.execute("UPDATE attribute_options SET value=?,label=?,swatch=?,display_order=?,active=?,selectable=?,version=version+1 WHERE id=?",v.value(),v.label(),v.swatch(),Objects.requireNonNullElse(v.displayOrder(),0),Objects.requireNonNullElse(v.active(),true),input.selectable()==null?current.get("selectable"):input.selectable(),id);return detail("option",id);
 }
 public void delete(String kind,UUID id,long version){var current=db.one("SELECT * FROM "+table(kind)+" WHERE id=? FOR UPDATE",id);Problem.version(Db.version(current),version);var used=usage(kind,id);if(!used.isEmpty())throw Problem.conflict("CATALOG_ITEM_IN_USE","Se utiliza en: "+used.stream().limit(8).map(row->row.get("label").toString()).reduce((a,b)->a+", "+b).orElse("catálogo"));db.execute("DELETE FROM "+table(kind)+" WHERE id=?",id);}
 public void validateAssignments(UUID product,Api.Draft next){
  var rows=db.rows("SELECT content FROM product_drafts WHERE product_id=? UNION ALL SELECT r.content FROM products p JOIN product_revisions r ON r.id=p.published_revision WHERE p.id=? LIMIT 1",product,product);
  Api.Draft old=rows.isEmpty()?null:json.read(rows.getFirst().get("content"),Api.Draft.class);
  if((old==null||!next.categoryId().equals(old.categoryId()))&&db.rows("SELECT id FROM categories WHERE id=? AND selectable=true AND active=true FOR SHARE",next.categoryId()).isEmpty())throw Problem.conflict("CATEGORY_NOT_SELECTABLE","La categoría no admite nuevas asignaciones.");
  for(UUID id:Objects.requireNonNullElse(next.collectionIds(),List.<UUID>of()))if((old==null||old.collectionIds()==null||!old.collectionIds().contains(id))&&db.rows("SELECT id FROM collections WHERE id=? AND selectable=true AND active=true FOR SHARE",id).isEmpty())throw Problem.conflict("COLLECTION_NOT_SELECTABLE","La colección no admite nuevas asignaciones.");
  for(var variant:next.variants())for(var attr:variant.attributes().entrySet()){
   boolean retained=old!=null&&old.variants().stream().anyMatch(v->v.sku().equals(variant.sku())&&Objects.equals(v.attributes().get(attr.getKey()),attr.getValue()));
   if(!retained){var options=db.rows("SELECT o.id,o.selectable,o.active FROM attribute_options o JOIN attribute_definitions d ON d.id=o.attribute_id WHERE d.code=? AND o.value=? FOR SHARE OF o",attr.getKey(),attr.getValue());if(options.stream().anyMatch(o->Boolean.FALSE.equals(o.get("selectable"))||Boolean.FALSE.equals(o.get("active"))))throw Problem.conflict("OPTION_NOT_SELECTABLE","La opción "+attr.getValue()+" no admite nuevas asignaciones.");}
  }
 }
}
