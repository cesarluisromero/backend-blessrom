package com.blessrom.commerce;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

/** A single fixed storefront page; drafts are never returned by the public API. */
@ApplicationScoped
@Transactional
public class HomeService {
    @Inject Db db;
    @Inject Json json;
    @Inject MediaService media;
    @Inject ActivityService activity;

    @RegisterForReflection
    public record Target(@NotBlank @Pattern(regexp="catalog|category|collection|collections") String type, UUID id) {}
    @RegisterForReflection
    public record Action(@NotBlank @Size(max=80) String label, @NotNull @Valid Target target) {}
    @RegisterForReflection
    public record HomeSlide(
        @NotNull @Size(max=100) String eyebrow,
        @NotBlank @Size(max=180) String title,
        @NotNull @Size(max=1000) String description,
        UUID heroAssetId,
        @NotNull @Size(max=300) String heroAlt,
        @DecimalMin("0") @DecimalMax("100") double frameX,
        @DecimalMin("0") @DecimalMax("100") double frameY,
        @NotNull @Valid Action primary,
        @Valid Action secondary,
        UUID mobileAssetId,
        Boolean enabled) {}
    @RegisterForReflection
    public record HomeContent(
        @NotNull @Size(max=100) String eyebrow,
        @NotBlank @Size(max=180) String title,
        @NotNull @Size(max=1000) String description,
        UUID heroAssetId,
        @NotNull @Size(max=300) String heroAlt,
        @DecimalMin("0") @DecimalMax("100") double frameX,
        @DecimalMin("0") @DecimalMax("100") double frameY,
        @NotNull @Valid Action primary,
        @Valid Action secondary,
        @NotNull @Size(max=6) List<@NotNull @Valid Action> shortcuts,
        @Size(max=6) List<@NotNull @Valid HomeSlide> slides) {
        /** Keeps already stored revisions and older clients readable during the additive rollout. */
        public HomeContent(String eyebrow,String title,String description,UUID heroAssetId,String heroAlt,double frameX,double frameY,Action primary,Action secondary,List<Action> shortcuts) {
            this(eyebrow,title,description,heroAssetId,heroAlt,frameX,frameY,primary,secondary,shortcuts,null);
        }
    }
    @RegisterForReflection
    public record SaveDraft(@Min(0) long version, @NotNull @Valid HomeContent content) {}

    public Map<String,Object> admin() {
        var page=db.one("SELECT * FROM storefront_pages WHERE page_key='home'");
        HomeContent draft=read(page.get("draft_content")), published=null;
        if(page.get("published_revision_id")!=null)
            published=read(db.one("SELECT content FROM storefront_revisions WHERE id=?",page.get("published_revision_id")).get("content"));
        var result=new LinkedHashMap<String,Object>();
        result.put("version",page.get("version"));
        result.put("draft",draft);result.put("published",published);
        result.put("publishedRevisionId",page.get("published_revision_id"));
        result.put("publishedAt",page.get("published_at"));
        result.put("draftMedia",hero(draft));result.put("publishedMedia",hero(published));
        result.put("draftSlides",slidePayloads(draft,false));result.put("publishedSlides",slidePayloads(published,false));
        return result;
    }

    public Map<String,Object> saveDraft(SaveDraft input,String actor) {
        var page=lock();Problem.version(Db.version(page),input.version());
        validate(input.content(),false);
        db.execute("UPDATE storefront_pages SET draft_content=?::jsonb,version=version+1,updated_at=now() WHERE page_key='home'",json.write(input.content()));
        activity.record("HOME_DRAFT_SAVED","home",null,"Borrador de portada guardado",actor);
        return admin();
    }

    public Map<String,Object> publish(long expected,String actor) {
        var page=lock();Problem.version(Db.version(page),expected);
        HomeContent content=read(page.get("draft_content"));
        if(content==null) throw Problem.conflict("HOME_DRAFT_REQUIRED","Guarda un borrador antes de publicar la portada.");
        validate(content,true);
        UUID revision=UUID.randomUUID();
        db.execute("INSERT INTO storefront_revisions(id,page_key,content,created_by) VALUES(?,'home',?::jsonb,?)",revision,json.write(content),actor);
        db.execute("UPDATE storefront_pages SET published_revision_id=?,published_at=now(),draft_content=NULL,version=version+1,updated_at=now() WHERE page_key='home'",revision);
        activity.record("HOME_PUBLISHED","home",revision,"Portada publicada",actor);
        return admin();
    }

    public Map<String,Object> publicHome() {
        var rows=db.rows("SELECT r.id,r.content FROM storefront_pages p JOIN storefront_revisions r ON r.id=p.published_revision_id WHERE p.page_key='home'");
        if(rows.isEmpty()) return null;
        var row=rows.getFirst();HomeContent content=read(row.get("content"));
        var result=new LinkedHashMap<String,Object>();
        result.put("revisionId",row.get("id"));result.put("content",content);
        var hero=hero(content);if(hero!=null)result.put("heroMedia",hero);
        result.put("slides",slidePayloads(content,true));
        return result;
    }

    private Map<String,Object> lock() {return db.one("SELECT * FROM storefront_pages WHERE page_key='home' FOR UPDATE");}
    private HomeContent read(Object value) {return value==null?null:json.read(value,HomeContent.class);}
    private Map<String,Object> hero(HomeContent content) {return content==null||content.heroAssetId()==null?null:media.homeAsset(content.heroAssetId());}

    private List<HomeSlide> slides(HomeContent content) {
        if(content==null)return List.of();
        if(content.slides()!=null&&!content.slides().isEmpty())return content.slides();
        return List.of(new HomeSlide(content.eyebrow(),content.title(),content.description(),content.heroAssetId(),content.heroAlt(),content.frameX(),content.frameY(),content.primary(),content.secondary(),null,true));
    }

    private List<Map<String,Object>> slidePayloads(HomeContent content,boolean publicOnly) {
        var result=new ArrayList<Map<String,Object>>();
        for(var slide:slides(content)) {
            boolean enabled=slide.enabled()==null||slide.enabled();
            if(publicOnly&&(!enabled||slide.heroAssetId()==null))continue;
            var item=new LinkedHashMap<String,Object>();item.put("content",slide);
            if(slide.heroAssetId()!=null)item.put("heroMedia",media.homeAsset(slide.heroAssetId()));
            if(slide.mobileAssetId()!=null)item.put("mobileMedia",media.homeAsset(slide.mobileAssetId()));
            result.add(item);
        }
        return result;
    }

    void validate(HomeContent content,boolean publishing) {
        validateStructure(content);
        if(content.heroAssetId()!=null)media.homeAsset(content.heroAssetId());
        boolean managedSlider=content.slides()!=null;
        int publishedSlides=0;
        for(var slide:slides(content)) {
            boolean enabled=slide.enabled()==null||slide.enabled();
            if(slide.heroAssetId()!=null)media.homeAsset(slide.heroAssetId());
            if(slide.mobileAssetId()!=null)media.homeAsset(slide.mobileAssetId());
            if(enabled&&slide.heroAssetId()!=null)publishedSlides++;
            if(publishing&&managedSlider&&enabled&&slide.heroAssetId()==null)
                throw new Problem(400,"HOME_SLIDE_IMAGE_REQUIRED","Cada diapositiva activa necesita una fotografía.");
        }
        if(publishing&&managedSlider&&publishedSlides==0)
            throw new Problem(400,"HOME_SLIDES_REQUIRED","Agrega al menos una diapositiva activa con fotografía antes de publicar.");
        var actions=new ArrayList<Action>();actions.add(content.primary());
        if(content.secondary()!=null)actions.add(content.secondary());actions.addAll(content.shortcuts());
        for(var slide:slides(content)){actions.add(slide.primary());if(slide.secondary()!=null)actions.add(slide.secondary());}
        for(var action:actions) {
            var target=action.target();
            if("category".equals(target.type())) {
                if(db.rows("SELECT id FROM categories WHERE id=? AND active=true FOR SHARE",target.id()).isEmpty())
                    throw new Problem(400,"HOME_TARGET_UNAVAILABLE","Elige una categoría activa para el acceso.");
            } else if("collection".equals(target.type())) {
                if(db.rows("SELECT id FROM collections WHERE id=? AND active=true AND (starts_at IS NULL OR starts_at<=now()) AND (ends_at IS NULL OR ends_at>now()) FOR SHARE",target.id()).isEmpty())
                    throw new Problem(400,"HOME_TARGET_UNAVAILABLE","Elige una colección activa y vigente para el acceso.");
            }
        }
        if(content.slides()!=null)for(var slide:content.slides())validateSlide(slide);
    }

    private static void validateSlide(HomeSlide slide) {
        if(slide==null||slide.title()==null||slide.title().isBlank()||slide.title().length()>180||slide.eyebrow()==null||slide.eyebrow().length()>100
            ||slide.description()==null||slide.description().length()>1000||slide.heroAlt()==null||slide.heroAlt().length()>300
            ||!Double.isFinite(slide.frameX())||!Double.isFinite(slide.frameY())||slide.frameX()<0||slide.frameX()>100||slide.frameY()<0||slide.frameY()>100||slide.primary()==null)
            throw new Problem(400,"INVALID_HOME_SLIDE","Revisa los textos, la imagen y el encuadre de cada diapositiva.");
        if(slide.heroAssetId()!=null&&slide.heroAlt().isBlank())throw new Problem(400,"HOME_IMAGE_ALT_REQUIRED","Describe cada imagen de portada para lectores de pantalla.");
        validateAction(slide.primary());if(slide.secondary()!=null)validateAction(slide.secondary());
    }

    private static void validateAction(Action action) {
        if(action==null||action.label()==null||action.label().isBlank()||action.label().length()>80||action.target()==null)
            throw new Problem(400,"INVALID_HOME_ACTION","Cada acceso necesita un texto y un destino.");
        var target=action.target();
        if(target.type()==null||!Set.of("catalog","category","collection","collections").contains(target.type())
            ||(Set.of("category","collection").contains(target.type())!=(target.id()!=null)))
            throw new Problem(400,"INVALID_HOME_TARGET","Elige un destino interno válido para el acceso.");
    }

    static void validateStructure(HomeContent content) {
        if(content==null||content.title()==null||content.title().isBlank()||content.title().length()>180||content.eyebrow()==null||content.eyebrow().length()>100
            ||content.description()==null||content.description().length()>1000||content.heroAlt()==null||content.heroAlt().length()>300
            ||!Double.isFinite(content.frameX())||!Double.isFinite(content.frameY())||content.frameX()<0||content.frameX()>100||content.frameY()<0||content.frameY()>100
            ||content.shortcuts()==null||content.shortcuts().size()>6||content.primary()==null)
            throw new Problem(400,"INVALID_HOME_CONTENT","Revisa los textos, los accesos y el encuadre de la portada.");
        if(content.heroAssetId()!=null&&content.heroAlt().isBlank())
            throw new Problem(400,"HOME_IMAGE_ALT_REQUIRED","Describe la imagen de portada para lectores de pantalla.");
        var actions=new ArrayList<Action>();actions.add(content.primary());
        if(content.secondary()!=null)actions.add(content.secondary());actions.addAll(content.shortcuts());
        for(var action:actions) {
            validateAction(action);
        }
    }
}
