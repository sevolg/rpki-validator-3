--
-- The BSD License
--
-- Copyright (c) 2010-2018 RIPE NCC
-- All rights reserved.
--
-- Redistribution and use in source and binary forms, with or without
-- modification, are permitted provided that the following conditions are met:
--   - Redistributions of source code must retain the above copyright notice,
--     this list of conditions and the following disclaimer.
--   - Redistributions in binary form must reproduce the above copyright notice,
--     this list of conditions and the following disclaimer in the documentation
--     and/or other materials provided with the distribution.
--   - Neither the name of the RIPE NCC nor the names of its contributors may be
--     used to endorse or promote products derived from this software without
--     specific prior written permission.
--
-- THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
-- AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
-- IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
-- ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
-- LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
-- CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
-- SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
-- INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
-- CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
-- ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
-- POSSIBILITY OF SUCH DAMAGE.
--

CREATE TABLE setting (
    id BIGINT NOT NULL,
    version INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    key VARCHAR NOT NULL,
    value VARCHAR NOT NULL,
    CONSTRAINT setting__pk PRIMARY KEY (id),
    CONSTRAINT setting__key_unique UNIQUE (key)
);
CREATE INDEX setting__key_idx ON setting (key ASC);

CREATE TABLE trust_anchor (
    id BIGINT NOT NULL,
    version INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    name VARCHAR_IGNORECASE(1000) NOT NULL,
    subject_public_key_info VARCHAR(2000) NOT NULL,
    rsync_prefetch_uri VARCHAR(2000),
    encoded_certificate BINARY,
    CONSTRAINT trust_anchor__pk PRIMARY KEY (id),
    CONSTRAINT trust_anchor__subject_public_key_info_unique UNIQUE (subject_public_key_info)
);
CREATE INDEX trust_anchor__name_idx ON trust_anchor (name ASC);

CREATE TABLE trust_anchor_locations (
    trust_anchor_id BIGINT NOT NULL,
    locations_order INT NOT NULL,
    locations VARCHAR(16000) NOT NULL,
    CONSTRAINT trust_anchor_locations__pk PRIMARY KEY (trust_anchor_id, locations_order),
    CONSTRAINT trust_anchor_locations__trust_anchor_fk FOREIGN KEY (trust_anchor_id) REFERENCES trust_anchor (id) ON DELETE CASCADE
);

CREATE TABLE rpki_repository (
    id BIGINT NOT NULL,
    version INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    type VARCHAR NOT NULL,
    status VARCHAR NOT NULL,
    last_downloaded_at TIMESTAMP,
    rsync_repository_uri VARCHAR(16000),
    rrdp_notify_uri VARCHAR(16000),
    rrdp_session_id VARCHAR(100),
    rrdp_serial DECIMAL(1000, 0),
    CONSTRAINT rpki_repository__pk PRIMARY KEY (id),
    CONSTRAINT rpki_repository__rrdp_notify_uri_unique UNIQUE (rrdp_notify_uri),
    CONSTRAINT rpki_repository__rsync_repository_uri_unique UNIQUE (rsync_repository_uri)
);

CREATE TABLE rpki_repository_trust_anchors (
    rpki_repository_id BIGINT NOT NULL,
    trust_anchor_id BIGINT NOT NULL,
    CONSTRAINT rpki_repository_trust_anchors__pk PRIMARY KEY (rpki_repository_id, trust_anchor_id),
    CONSTRAINT rpki_repository_trust_anchors__trust_anchor_fk FOREIGN KEY (trust_anchor_id) REFERENCES trust_anchor (id) ON DELETE RESTRICT,
    CONSTRAINT rpki_repository_trust_anchors__rpki_repository_fk FOREIGN KEY (rpki_repository_id) REFERENCES rpki_repository (id) ON DELETE RESTRICT
);
CREATE INDEX rpki_repository_trust_anchors__trust_anchor_id_idx ON rpki_repository_trust_anchors (trust_anchor_id ASC);

CREATE TABLE rpki_object (
    id BIGINT NOT NULL,
    version INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    type VARCHAR NOT NULL,
    serial_number DECIMAL(1000, 0),
    signing_time TIMESTAMP,
    authority_key_identifier BINARY(32),
    sha256 BINARY(32) NOT NULL,
    encoded BINARY,
    CONSTRAINT rpki_object__pk PRIMARY KEY (id)
);
CREATE UNIQUE INDEX rpki_object__sha256_idx ON rpki_object (sha256 ASC);
CREATE INDEX rpki_object__authority_key_idenfitifier_idx ON rpki_object (authority_key_identifier ASC, type ASC, serial_number DESC, signing_time DESC, id DESC);

CREATE TABLE rpki_object_locations (
    rpki_object_id BIGINT NOT NULL,
    locations VARCHAR(16000) NOT NULL,
    CONSTRAINT rpki_object_locations__pk PRIMARY KEY (rpki_object_id, locations),
    CONSTRAINT rpki_object_locations__rpki_object_fk FOREIGN KEY (rpki_object_id) REFERENCES rpki_object (id) ON DELETE CASCADE
);

CREATE TABLE rpki_object_roa_prefixes (
    rpki_object_id BIGINT NOT NULL,
    roa_prefixes_order INTEGER NOT NULL,
    prefix VARCHAR NOT NULL,
    maximum_length INTEGER,
    effective_length INTEGER NOT NULL,
    asn BIGINT NOT NULL,
    CONSTRAINT rpki_object_roa_prefixes__pk PRIMARY KEY (rpki_object_id, roa_prefixes_order),
    CONSTRAINT rpki_object_roa_prefixes__rpki_object_fk FOREIGN KEY (rpki_object_id) REFERENCES rpki_object (id) ON DELETE CASCADE
);

CREATE TABLE validation_run (
    type CHAR(2) NOT NULL,
    id BIGINT NOT NULL,
    version INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    status VARCHAR NOT NULL,

    -- Trust anchor validation run
    trust_anchor_id BIGINT,
    trust_anchor_certificate_uri VARCHAR(16000),

    -- RPKI repository validation run
    added_object_count INTEGER,

    -- RRDP repository validation run
    rpki_repository_id BIGINT,

    CONSTRAINT validation_run__pk PRIMARY KEY (id),
    CONSTRAINT validation_run__trust_anchor_fk FOREIGN KEY (trust_anchor_id) REFERENCES trust_anchor (id) ON DELETE RESTRICT,
    CONSTRAINT validation_run__rpki_repository_fk FOREIGN KEY (rpki_repository_id) REFERENCES rpki_repository (id) ON DELETE RESTRICT,
);
CREATE INDEX validation_run__trust_anchor_id_idx ON validation_run (trust_anchor_id ASC, created_at DESC);
CREATE INDEX validation_run__rpki_repository_id_idx ON validation_run (rpki_repository_id ASC, created_at DESC);

CREATE TABLE validation_check (
    id BIGINT NOT NULL,
    version INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    validation_run_id BIGINT NOT NULL,
    location VARCHAR(16000) NOT NULL,
    status VARCHAR NOT NULL,
    key VARCHAR NOT NULL,
    CONSTRAINT validation_check__pk PRIMARY KEY (id),
    CONSTRAINT validation_check__validation_run_fk FOREIGN KEY (validation_run_id) REFERENCES validation_run (id) ON DELETE CASCADE
);
CREATE INDEX validation_check__validation_run_id_idx ON validation_check (validation_run_id ASC, id ASC);

CREATE TABLE validation_check_parameters (
    validation_check_id BIGINT NOT NULL,
    parameters_order INTEGER NOT NULL,
    parameters VARCHAR NOT NULL,
    CONSTRAINT validation_check_parameters__pk PRIMARY KEY (validation_check_id, parameters_order),
    CONSTRAINT validation_check_parameters__validation_check_fk FOREIGN KEY (validation_check_id) REFERENCES validation_check (id) ON DELETE CASCADE
);

CREATE TABLE validation_run_validated_objects (
    validation_run_id BIGINT NOT NULL,
    rpki_object_id BIGINT NOT NULL,
    CONSTRAINT validation_run_validated_objects__pk PRIMARY KEY (validation_run_id, rpki_object_id),
    CONSTRAINT validation_run_validated_objects__validation_run_fk FOREIGN KEY (validation_run_id) REFERENCES validation_run (id) ON DELETE CASCADE,
    CONSTRAINT validation_run_validated_objects__rpki_object_fk FOREIGN KEY (rpki_object_id) REFERENCES rpki_object (id) ON DELETE CASCADE
);
CREATE INDEX validation_run_validated_objects__rpki_object_idx ON validation_run_validated_objects (rpki_object_id);

-- RSYNC repository validation run
CREATE TABLE validation_run_rpki_repositories (
    validation_run_id BIGINT NOT NULL,
    rpki_repository_id BIGINT NOT NULL,
    CONSTRAINT validation_run_rpki_repositories__pk PRIMARY KEY (validation_run_id, rpki_repository_id),
    CONSTRAINT validation_run_rpki_repositories__validation_run_fk FOREIGN KEY (validation_run_id) REFERENCES validation_run (id) ON DELETE CASCADE,
    CONSTRAINT validation_run_rpki_repositories__rpki_repository_fk FOREIGN KEY (rpki_repository_id) REFERENCES rpki_repository (id) ON DELETE CASCADE
);
CREATE INDEX validation_run_rpki_repositories__rpki_repository_idx ON validation_run_rpki_repositories (rpki_repository_id);
