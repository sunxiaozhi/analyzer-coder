package com.analyzercoder.infrastructure.persistence.model;
import java.time.Instant;import java.util.UUID;
public record KnowledgeRevisionRow(UUID cardId,int revision,UUID repositoryId,String title,String cardType,String content,String[] tags,String status,UUID changedBy,Instant changedAt){}
