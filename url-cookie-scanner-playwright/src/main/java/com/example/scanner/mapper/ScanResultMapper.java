package com.example.scanner.mapper;

import com.example.scanner.dto.CookieDto;
import com.example.scanner.dto.ScanResultDto;
import com.example.scanner.entity.CookieEntity;
import com.example.scanner.entity.ScanResultEntity;
import com.example.scanner.enums.SameSite;
import com.example.scanner.enums.Source;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class ScanResultMapper {

    // Convert CookieDto to CookieEntity with subdomain support
    public static CookieEntity cookieDtoToEntity(CookieDto dto) {
        return new CookieEntity(
                dto.getName(),
                dto.getUrl(),
                dto.getDomain(),
                dto.getPath(),
                dto.getExpires(),
                dto.isSecure(),
                dto.isHttpOnly(),
                dto.getSameSite() != null
                        ? SameSite.valueOf(dto.getSameSite().name())
                        : null,
                dto.getSource() != null
                        ? Source.valueOf(dto.getSource().name())
                        : null,
                dto.getCategory(),
                dto.getDescription(),
                dto.getDescription_gpt(),
                dto.getSubdomainName() != null ? dto.getSubdomainName() : "main"
        );
    }

    // Convert CookieEntity to CookieDto with subdomain support
    public static CookieDto cookieEntityToDto(CookieEntity entity) {
        return new CookieDto(
                entity.getName(),
                entity.getUrl(),
                entity.getDomain(),
                entity.getPath(),
                entity.getExpires(),
                entity.isSecure(),
                entity.isHttpOnly(),
                entity.getSameSite(),
                entity.getSource(),
                entity.getCategory(),
                entity.getDescription(),
                entity.getDescription_gpt(),
                entity.getSubdomainName() != null ? entity.getSubdomainName() : "main"
        );
    }

    // Convert ScanResultDto to ScanResultEntity
    public static ScanResultEntity toEntity(ScanResultDto dto) {
        ScanResultEntity entity = new ScanResultEntity();
        entity.setId(dto.getId());
        entity.setTransactionId(dto.getTransactionId());
        entity.setStatus(dto.getStatus());
        entity.setErrorMessage(dto.getErrorMessage());
        entity.setUrl(dto.getUrl());

        // Group flat cookie list by subdomain
        if (dto.getCookies() != null) {
            Map<String, List<CookieEntity>> cookiesBySubdomain = dto.getCookies()
                    .stream()
                    .map(ScanResultMapper::cookieDtoToEntity)
                    .collect(Collectors.groupingBy(
                            cookie -> cookie.getSubdomainName() != null ? cookie.getSubdomainName() : "main"
                    ));
            entity.setCookiesBySubdomain(cookiesBySubdomain);
        }

        return entity;
    }

    public static ScanResultDto toDto(ScanResultEntity entity) {
        ScanResultDto dto = new ScanResultDto();
        dto.setId(entity.getId());
        dto.setTransactionId(entity.getTransactionId());
        dto.setStatus(entity.getStatus());
        dto.setErrorMessage(entity.getErrorMessage());
        dto.setUrl(entity.getUrl());

        // Flatten grouped cookies to single list for DTO
        if (entity.getCookiesBySubdomain() != null) {
            List<CookieDto> allCookies = entity.getCookiesBySubdomain().values()
                    .stream()
                    .flatMap(List::stream)
                    .map(ScanResultMapper::cookieEntityToDto)
                    .collect(Collectors.toList());
            dto.setCookies(allCookies);
        }

        return dto;
    }

    // Helper method to create backward compatible CookieEntity (without subdomain)
    public static CookieEntity createLegacyCookieEntity(String name, String url, String domain,
                                                        String path, java.time.Instant expires,
                                                        boolean secure, boolean httpOnly,
                                                        SameSite sameSite, Source source) {
        return new CookieEntity(name, url, domain, path, expires, secure, httpOnly,
                sameSite, source, null, null, null, "main");
    }

    // Helper method to create backward compatible CookieDto (without subdomain)
    public static CookieDto createLegacyCookieDto(String name, String url, String domain,
                                                  String path, java.time.Instant expires,
                                                  boolean secure, boolean httpOnly,
                                                  SameSite sameSite, Source source) {
        return new CookieDto(name, url, domain, path, expires, secure, httpOnly,
                sameSite, source, null, null, null, "main");
    }
}