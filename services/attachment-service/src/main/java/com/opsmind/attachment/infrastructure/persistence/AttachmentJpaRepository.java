package com.opsmind.attachment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface AttachmentJpaRepository extends JpaRepository<AttachmentJpaEntity, UUID> {
}
