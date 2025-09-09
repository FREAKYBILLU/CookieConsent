package com.example.scanner.entity;

import com.example.scanner.enums.SameSite;
import com.example.scanner.enums.Source;
import lombok.Data;
import java.time.Instant;

@Data
public class CookieEntity {
  private String name;
  private String url;
  private String domain;
  private String path;
  private Instant expires;
  private boolean secure;
  private boolean httpOnly;
  private SameSite sameSite;
  private Source source;
  private String category;
  private String description;
  private String description_gpt;

  // NEW FIELD: Store the subdomain name where this cookie was found
  private String subdomainName;

  public CookieEntity() {
  }

  // Updated constructor with subdomain field
  public CookieEntity(String name, String url, String domain, String path, Instant expires,
                      boolean secure, boolean httpOnly, SameSite sameSite, Source source,
                      String category, String description, String description_gpt, String subdomainName) {
    this.name = name;
    this.url = url;
    this.domain = domain;
    this.path = path;
    this.expires = expires;
    this.secure = secure;
    this.httpOnly = httpOnly;
    this.sameSite = sameSite;
    this.source = source;
    this.category = category;
    this.description = description;
    this.description_gpt = description_gpt;
    this.subdomainName = subdomainName;
  }

  // Backward compatibility constructor
  public CookieEntity(String name, String url, String domain, String path, Instant expires,
                      boolean secure, boolean httpOnly, SameSite sameSite, Source source,
                      String category, String description, String description_gpt) {
    this(name, url, domain, path, expires, secure, httpOnly, sameSite, source,
            category, description, description_gpt, "main");
  }
}