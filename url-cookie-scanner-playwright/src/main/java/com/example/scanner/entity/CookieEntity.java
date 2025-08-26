package com.example.scanner.entity;

import com.example.scanner.enums.SameSite;
import com.example.scanner.enums.Source;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

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
  private String category;     // New field for cookie category
  private String description;  // New field for cookie description

  public CookieEntity() {
  }

  public CookieEntity(String name, String url, String domain, String path, Instant expires,
                      boolean secure, boolean httpOnly, SameSite sameSite, Source source) {
    this.name = name;
    this.url = url;
    this.domain = domain;
    this.path = path;
    this.expires = expires;
    this.secure = secure;
    this.httpOnly = httpOnly;
    this.sameSite = sameSite;
    this.source = source;
  }

  // Constructor with new fields
  public CookieEntity(String name, String url, String domain, String path, Instant expires,
                      boolean secure, boolean httpOnly, SameSite sameSite, Source source,
                      String category, String description) {
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
  }

  // Existing getters and setters
  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getDomain() {
    return domain;
  }

  public void setDomain(String domain) {
    this.domain = domain;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public Instant getExpires() {
    return expires;
  }

  public void setExpires(Instant expires) {
    this.expires = expires;
  }

  public boolean isSecure() {
    return secure;
  }

  public void setSecure(boolean secure) {
    this.secure = secure;
  }

  public boolean isHttpOnly() {
    return httpOnly;
  }

  public void setHttpOnly(boolean httpOnly) {
    this.httpOnly = httpOnly;
  }

  public SameSite getSameSite() {
    return sameSite;
  }

  public void setSameSite(SameSite sameSite) {
    this.sameSite = sameSite;
  }

  public Source getSource() {
    return source;
  }

  public void setSource(Source source) {
    this.source = source;
  }

  // New getters and setters
  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }
}