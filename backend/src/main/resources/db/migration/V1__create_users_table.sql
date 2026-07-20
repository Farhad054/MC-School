-- Users of the platform. One table for all roles (ADMIN, TEACHER, STUDENT):
-- the MVP has no role-specific profile data, only the student -> teacher link.
--
-- Account lifecycle (from the PRD):
--   * There is no self-registration.
--   * An admin creates teacher accounts; a teacher creates student accounts.
--   * New accounts start as INVITED with an invitation token and no password.
--   * The invited person opens the invitation link, sets a password and the
--     account becomes ACTIVE.
CREATE TABLE users (
    id                    UUID         PRIMARY KEY,
    full_name             VARCHAR(100) NOT NULL,
    email                 VARCHAR(255) NOT NULL,
    -- BCrypt hash; NULL while the account is still INVITED.
    password_hash         VARCHAR(100),
    role                  VARCHAR(20)  NOT NULL
        CONSTRAINT users_role_check CHECK (role IN ('ADMIN', 'TEACHER', 'STUDENT')),
    status                VARCHAR(20)  NOT NULL
        CONSTRAINT users_status_check CHECK (status IN ('INVITED', 'ACTIVE')),
    -- The teacher who owns this student. NULL for admins and teachers.
    teacher_id            UUID
        CONSTRAINT fk_users_teacher REFERENCES users (id),
    invitation_token      VARCHAR(100),
    invitation_expires_at TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    -- Optimistic locking (JPA @Version).
    version               BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT users_email_unique UNIQUE (email),
    CONSTRAINT users_invitation_token_unique UNIQUE (invitation_token)
);

-- Teachers list their own students frequently.
CREATE INDEX idx_users_teacher_id ON users (teacher_id);
