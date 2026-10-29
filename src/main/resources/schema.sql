CREATE TABLE IF NOT EXISTS team_member
(
    id
    INTEGER
    PRIMARY
    KEY,
    name
    TEXT
    NOT
    NULL
    UNIQUE
);
CREATE TABLE IF NOT EXISTS video_source
(
    id
    INTEGER
    PRIMARY
    KEY,
    root_path
    TEXT
    NOT
    NULL
    UNIQUE,
    enabled
    INTEGER
    NOT
    NULL
    DEFAULT
    1,
    created_at
    TEXT
    NOT
    NULL,
    updated_at
    TEXT
    NOT
    NULL
);
CREATE TABLE IF NOT EXISTS video_asset
(
    id
    INTEGER
    PRIMARY
    KEY,
    source_id
    INTEGER
    NULL
    REFERENCES
    video_source
(
    id
),
    absolute_path TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL,
    file_size INTEGER NOT NULL, modified_at INTEGER NOT NULL,
    origin_type TEXT NOT NULL CHECK
(
    origin_type
    IN
(
    'SCANNED',
    'PICKED'
)),
    created_at TEXT NOT NULL, updated_at TEXT NOT NULL
    );
CREATE TABLE IF NOT EXISTS match_project
(
    id
    INTEGER
    PRIMARY
    KEY,
    name
    TEXT
    NOT
    NULL,
    video_asset_id
    INTEGER
    NOT
    NULL
    REFERENCES
    video_asset
(
    id
), duration_ms INTEGER NOT NULL CHECK
(
    duration_ms >
    0
),
    player_a TEXT NOT NULL, player_b TEXT NOT NULL, target_wins INTEGER NOT NULL CHECK
(
    target_wins >= 1
),
    scoreboard_template TEXT NOT NULL DEFAULT 'CLASSIC' CHECK
(
    scoreboard_template
    IN
(
    'CLASSIC',
    'MODERN'
)),
    first_server TEXT NOT NULL DEFAULT 'A' CHECK
(
    first_server
    IN
(
    'A',
    'B'
)),
    show_project_name INTEGER NOT NULL DEFAULT 0 CHECK
(
    show_project_name
    IN
(
    0,
    1
)),
    created_at TEXT NOT NULL, updated_at TEXT NOT NULL
    );
CREATE TABLE IF NOT EXISTS score_event
(
    id
    INTEGER
    PRIMARY
    KEY,
    match_id
    INTEGER
    NOT
    NULL
    REFERENCES
    match_project
(
    id
) ON DELETE CASCADE,
    video_time_ms INTEGER NOT NULL CHECK
(
    video_time_ms >= 0
),
    player_side TEXT NOT NULL CHECK
(
    player_side
    IN
(
    'A',
    'B'
)), sequence_no INTEGER NOT NULL,
    created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE
(
    match_id,
    sequence_no
)
    );
CREATE INDEX IF NOT EXISTS idx_score_event_match_timeline ON score_event(match_id, video_time_ms, sequence_no);
CREATE TABLE IF NOT EXISTS render_job
(
    id
    INTEGER
    PRIMARY
    KEY,
    match_id
    INTEGER
    NOT
    NULL
    REFERENCES
    match_project
(
    id
),
    template_code TEXT NOT NULL, status TEXT NOT NULL,
    progress_percent INTEGER NOT NULL DEFAULT 0,
    output_path TEXT NULL, failure_message TEXT NULL,
    created_at TEXT NOT NULL, started_at TEXT NULL, finished_at TEXT NULL
    );
CREATE INDEX IF NOT EXISTS idx_render_job_status ON render_job(status);
CREATE TABLE IF NOT EXISTS app_settings
(
    id INTEGER PRIMARY KEY CHECK
(
    id = 1
),
    player_a_name TEXT NOT NULL DEFAULT '甲',
    player_b_name TEXT NOT NULL DEFAULT '乙'
    );
CREATE TABLE IF NOT EXISTS scoreboard_template_config
(
    id
    INTEGER
    PRIMARY
    KEY,
    name
    TEXT
    NOT
    NULL,
    opacity
    INTEGER
    NOT
    NULL
    DEFAULT
    80
    CHECK
(
    opacity
    >=
    0
    AND
    opacity
    <=
    100
),
    bg_color TEXT NOT NULL DEFAULT '#000000',
    text_color TEXT NOT NULL DEFAULT '#ffffff'
    );
CREATE TABLE IF NOT EXISTS clip_project
(
    id
    INTEGER
    PRIMARY
    KEY,
    name
    TEXT
    NOT
    NULL,
    video_asset_id
    INTEGER
    NOT
    NULL
    REFERENCES
    video_asset
(
    id
),
    large_step INTEGER NOT NULL DEFAULT 10 CHECK
(
    large_step >
    0
),
    small_step INTEGER NOT NULL DEFAULT 5 CHECK
(
    small_step >
    0
),
    source_file_size INTEGER NOT NULL,
    source_modified_at INTEGER NOT NULL,
    total_frames INTEGER NULL,
    frame_index_status TEXT NOT NULL DEFAULT 'PENDING' CHECK
(
    frame_index_status
    IN
(
    'PENDING',
    'BUILDING',
    'READY',
    'FAILED'
)),
    created_at TEXT NOT NULL, updated_at TEXT NOT NULL
    );
CREATE TABLE IF NOT EXISTS clip_segment
(
    id
    INTEGER
    PRIMARY
    KEY,
    project_id
    INTEGER
    NOT
    NULL
    REFERENCES
    clip_project
(
    id
) ON DELETE CASCADE,
    segment_type TEXT NOT NULL CHECK
(
    segment_type
    IN
(
    'KEEP',
    'DROP'
)),
    start_frame INTEGER NOT NULL CHECK
(
    start_frame >=
    0
),
    end_frame INTEGER NOT NULL CHECK
(
    end_frame >
    start_frame
),
    featured INTEGER NOT NULL DEFAULT 0 CHECK
(
    featured IN
(
    0,
    1
)),
    created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
    CHECK (segment_type = 'KEEP' OR featured = 0)
    );
CREATE INDEX IF NOT EXISTS idx_clip_segment_project ON clip_segment(project_id, start_frame);
CREATE TABLE IF NOT EXISTS clip_render_job
(
    id
    INTEGER
    PRIMARY
    KEY,
    project_id
    INTEGER
    NOT
    NULL
    REFERENCES
    clip_project
(
    id
),
    status TEXT NOT NULL,
    progress_percent INTEGER NOT NULL DEFAULT 0,
    output_path TEXT NULL,
    output_asset_id INTEGER NULL,
    failure_message TEXT NULL,
    created_at TEXT NOT NULL, started_at TEXT NULL, finished_at TEXT NULL
    );
CREATE INDEX IF NOT EXISTS idx_clip_render_job_status ON clip_render_job(status);
