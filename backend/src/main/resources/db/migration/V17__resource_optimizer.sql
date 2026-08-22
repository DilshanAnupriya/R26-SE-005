-- Flyway migration to create tables for the AI Resource Optimizer module
CREATE TABLE optimizer_developers (
    id BIGSERIAL PRIMARY KEY,
    dev_id VARCHAR(50) NOT NULL,
    experience_level INT NOT NULL,
    skill_frontend INT NOT NULL,
    skill_backend INT NOT NULL,
    skill_db INT NOT NULL,
    current_workload INT NOT NULL,
    availability DOUBLE PRECISION NOT NULL,
    current_tasks INT NOT NULL,
    consistency DOUBLE PRECISION NOT NULL,
    learning_rate DOUBLE PRECISION NOT NULL
);

CREATE TABLE optimizer_developer_profiles (
    id BIGSERIAL PRIMARY KEY,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    age INT,
    email VARCHAR(255),
    password VARCHAR(255),
    phone_number VARCHAR(50),
    specialization VARCHAR(255),
    bio TEXT,
    developer_metrics_id BIGINT UNIQUE,
    CONSTRAINT fk_optimizer_dev_metrics FOREIGN KEY (developer_metrics_id) REFERENCES optimizer_developers(id) ON DELETE CASCADE
);

CREATE TABLE optimizer_sprints (
    id BIGSERIAL PRIMARY KEY,
    sprint_id VARCHAR(50) NOT NULL UNIQUE,
    start_date DATE,
    end_date DATE,
    status VARCHAR(50) NOT NULL
);

CREATE TABLE optimizer_tasks (
    id BIGSERIAL PRIMARY KEY,
    sprint_id BIGINT,
    developer_id BIGINT,
    task_type VARCHAR(255),
    task_complexity INT NOT NULL,
    story_points INT NOT NULL,
    req_frontend INT NOT NULL,
    req_backend INT NOT NULL,
    req_db INT NOT NULL,
    ambiguity DOUBLE PRECISION NOT NULL,
    dependency_risk DOUBLE PRECISION NOT NULL,
    completion_time DOUBLE PRECISION NOT NULL,
    defects INT NOT NULL,
    velocity_contribution DOUBLE PRECISION NOT NULL,
    blocked BOOLEAN NOT NULL,
    reopened BOOLEAN NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT fk_optimizer_task_sprint FOREIGN KEY (sprint_id) REFERENCES optimizer_sprints(id) ON DELETE SET NULL,
    CONSTRAINT fk_optimizer_task_developer FOREIGN KEY (developer_id) REFERENCES optimizer_developers(id) ON DELETE SET NULL
);

CREATE TABLE optimizer_allocations (
    id BIGSERIAL PRIMARY KEY,
    sprint_id BIGINT,
    task_id BIGINT,
    developer_id BIGINT,
    ml_score DOUBLE PRECISION,
    skill_match_score DOUBLE PRECISION,
    workload_balance DOUBLE PRECISION,
    final_score DOUBLE PRECISION,
    rank_position INT,
    status VARCHAR(50),
    explanation VARCHAR(2000),
    CONSTRAINT fk_optimizer_alloc_sprint FOREIGN KEY (sprint_id) REFERENCES optimizer_sprints(id) ON DELETE CASCADE,
    CONSTRAINT fk_optimizer_alloc_task FOREIGN KEY (task_id) REFERENCES optimizer_tasks(id) ON DELETE CASCADE,
    CONSTRAINT fk_optimizer_alloc_developer FOREIGN KEY (developer_id) REFERENCES optimizer_developers(id) ON DELETE CASCADE
);
