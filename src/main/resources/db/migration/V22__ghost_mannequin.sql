ALTER TABLE ai_studio_results
    ADD COLUMN cutout_approved boolean NOT NULL DEFAULT false,
    ADD COLUMN ghost_approved boolean NOT NULL DEFAULT false,
    ADD COLUMN cutout_revision integer NOT NULL DEFAULT 0 CHECK(cutout_revision BETWEEN 0 AND 3),
    ADD COLUMN ghost_revision integer NOT NULL DEFAULT 0 CHECK(ghost_revision BETWEEN 0 AND 3);

UPDATE ai_studio_results
SET cutout_approved=image_approved
WHERE job_id IN (SELECT id FROM ai_studio_jobs WHERE job_type='CUTOUT');
