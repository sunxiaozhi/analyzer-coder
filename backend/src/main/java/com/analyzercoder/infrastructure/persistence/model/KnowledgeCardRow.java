package com.analyzercoder.infrastructure.persistence.model;
import java.time.Instant;import java.util.UUID;
public record KnowledgeCardRow(UUID id,UUID repositoryId,String title,String cardType,String content,String[] tags,String status,int revision,Instant createdAt,Instant updatedAt){}
