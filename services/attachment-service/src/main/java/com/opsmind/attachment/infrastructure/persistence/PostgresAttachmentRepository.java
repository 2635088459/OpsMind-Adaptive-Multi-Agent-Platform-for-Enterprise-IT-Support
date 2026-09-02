package com.opsmind.attachment.infrastructure.persistence;

import com.opsmind.attachment.application.port.out.AttachmentRepository;
import com.opsmind.attachment.domain.Attachment;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class PostgresAttachmentRepository implements AttachmentRepository {

    private final AttachmentJpaRepository jpaRepository;

    public PostgresAttachmentRepository(AttachmentJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Attachment save(Attachment attachment) {
        return jpaRepository.save(AttachmentJpaEntity.fromDomain(attachment)).toDomain();
    }

    @Override
    public Optional<Attachment> findById(UUID attachmentId) {
        return jpaRepository.findById(attachmentId).map(AttachmentJpaEntity::toDomain);
    }
}
