package com.analyzercoder.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RepositoryCredentialMapper {
    List<Map<String, Object>> list(@Param("actorId") UUID actorId, @Param("includeAll") boolean includeAll);
    Map<String, Object> find(@Param("id") UUID id);
    int insert(@Param("id") UUID id, @Param("type") String type, @Param("displayName") String displayName,
        @Param("serverUrl") String serverUrl, @Param("username") String username,
        @Param("cipherText") String cipherText, @Param("iv") String iv,
        @Param("digest") String digest, @Param("algorithm") String algorithm,
        @Param("maskedValue") String maskedValue, @Param("actorId") UUID actorId);
    int update(@Param("id") UUID id, @Param("type") String type, @Param("displayName") String displayName,
        @Param("serverUrl") String serverUrl, @Param("username") String username,
        @Param("cipherText") String cipherText, @Param("iv") String iv,
        @Param("digest") String digest, @Param("algorithm") String algorithm,
        @Param("maskedValue") String maskedValue, @Param("actorId") UUID actorId,
        @Param("replaceSecret") boolean replaceSecret);
    int updateValidation(@Param("id") UUID id, @Param("status") String status,
        @Param("validatedAt") Instant validatedAt, @Param("error") String error);
    int updateStatus(@Param("id") UUID id, @Param("status") String status, @Param("actorId") UUID actorId);
    int countBindings(@Param("id") UUID id);
    int delete(@Param("id") UUID id);
    Map<String, Object> findBound(@Param("repositoryId") UUID repositoryId);
    List<Map<String, Object>> bindings(@Param("id") UUID id);
    int bind(@Param("repositoryId") UUID repositoryId, @Param("credentialId") UUID credentialId,
        @Param("actorId") UUID actorId);
    int unbind(@Param("repositoryId") UUID repositoryId);
}
