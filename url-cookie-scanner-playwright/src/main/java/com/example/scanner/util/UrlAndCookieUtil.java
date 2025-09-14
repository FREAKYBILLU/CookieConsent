package com.example.scanner.util;

import java.net.*;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import com.google.common.net.InternetDomainName;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;

public class UrlAndCookieUtil {

  private static final Pattern PROTOCOL_PATTERN = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*):.*");

  // Private IP ranges (RFC 1918, RFC 4193, etc.)
  private static final Set<String> PRIVATE_IP_PREFIXES = Set.of(
          "10.", "172.16.", "172.17.", "172.18.", "172.19.", "172.20.",
          "172.21.", "172.22.", "172.23.", "172.24.", "172.25.", "172.26.",
          "172.27.", "172.28.", "172.29.", "172.30.", "172.31.", "192.168.",
          "169.254.", // Link-local
          "fc00:", "fd00:", // IPv6 Unique Local
          "fe80:" // IPv6 Link-local
  );

  // Reserved/Special use domains and TLDs
  private static final Set<String> BLOCKED_DOMAINS = Set.of(
          "localhost", "test", "invalid", "example", "local"
  );


  // NEW (only blocks obvious internal services)
  private static final Pattern INTERNAL_SERVICE_PATTERN = Pattern.compile(
          ".*(\\.|^)(internal|staging|dev|test|local|private)\\."
  );
  // File extensions that shouldn't be scanned
  private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
          "pdf", "doc", "docx", "xls", "xlsx", "zip", "rar", "exe", "dmg",
          "pkg", "deb", "rpm", "tar", "gz", "mp4", "avi", "mp3", "wav", "jpg", "png", "gif"
  );

  public static boolean isHttpOrHttps(String url) {
    try {
      URI u = new URI(url);
      String s = u.getScheme();
      return s != null && (s.equalsIgnoreCase("http") || s.equalsIgnoreCase("https"));
    } catch (URISyntaxException e) {
      return false;
    }
  }

  public static String normalizeUrl(String url) throws URISyntaxException {
    URI u = new URI(url);
    if (u.getHost() == null) throw new URISyntaxException(url, "Host is required");
    String asciiHost = IDN.toASCII(u.getHost());
    int port = u.getPort();
    return new URI(
            u.getScheme().toLowerCase(Locale.ROOT),
            u.getUserInfo(),
            asciiHost.toLowerCase(Locale.ROOT),
            port,
            (u.getPath() == null || u.getPath().isEmpty()) ? "/" : u.getPath(),
            u.getQuery(),
            u.getFragment()
    ).toString();
  }

  /**
   * Comprehensive URL validation for cookie consent crawler
   * Validates security, accessibility, and appropriateness for cookie scanning
   */
  public static ValidationResult validateUrlForScanning(String url) {
    try {
      if (url == null || url.trim().isEmpty()) {
        return ValidationResult.invalid("URL cannot be null or empty");
      }

      String trimmedUrl = url.trim();
      String normalizedUrl;

      // Use regex to extract protocol if present
      Matcher matcher = PROTOCOL_PATTERN.matcher(trimmedUrl);
      if (matcher.matches()) {
        // URL has protocol
        String protocol = matcher.group(1).toLowerCase();
        if (!protocol.equals("http") && !protocol.equals("https")) {
          return ValidationResult.invalid("Only HTTP and HTTPS protocols are allowed");
        }
        normalizedUrl = trimmedUrl;
      } else {
        // No protocol - add https
        normalizedUrl = "https://" + trimmedUrl;
      }

      // Rest of your validation logic remains the same...
      URI uri = new URI(normalizedUrl);

      // Protocol validation (redundant check)
      if (!isHttpOrHttps(normalizedUrl)) {
        return ValidationResult.invalid("Only HTTP and HTTPS protocols are allowed");
      }
      // Host validation
      String host = uri.getHost();
      if (host == null || host.trim().isEmpty()) {
        return ValidationResult.invalid("URL must have a valid host");
      }

      host = host.toLowerCase();

      // ADDED: Domain format validation
      if (!isValidDomainName(host)) {
        return ValidationResult.invalid("Invalid domain name format");
      }

      // Check for blocked domains
      if (BLOCKED_DOMAINS.contains(host) || host.endsWith(".local") || host.endsWith(".test")) {
        return ValidationResult.invalid("Domain is reserved or for testing purposes");
      }

      // Check for localhost variations
      if (isLocalhost(host)) {
        return ValidationResult.invalid("Localhost and loopback addresses are not allowed");
      }

      // Check for private IP addresses
      if (isPrivateOrReservedIP(host)) {
        return ValidationResult.invalid("Private or reserved IP addresses are not allowed");
      }

      // Check for suspicious internal service patterns
      if (INTERNAL_SERVICE_PATTERN.matcher(normalizedUrl).matches()) {
        return ValidationResult.invalid("URL appears to target internal/admin services");
      }

      // Check file extensions
      String path = uri.getPath();
      if (path != null && hasBlockedFileExtension(path)) {
        return ValidationResult.invalid("URL points to a file type that cannot be scanned for cookies");
      }

      // Length validation
      if (normalizedUrl.length() > 2048) {
        return ValidationResult.invalid("URL exceeds maximum allowed length");
      }

      // Port validation (avoid scanning on unusual ports that might be internal services)
      int port = uri.getPort();
      if (port != -1 && !isAllowedPort(port)) {
        return ValidationResult.invalid("Port " + port + " is not allowed for scanning");
      }

      // URL structure validation (check path only, not the protocol part)
      if (path != null && (path.contains("..") || path.contains("//"))) {
        return ValidationResult.invalid("URL contains suspicious path traversal patterns");
      }

      return ValidationResult.valid(normalizedUrl);

    } catch (URISyntaxException e) {
      return ValidationResult.invalid("Invalid URL format: " + e.getMessage());
    }
  }

  /**
   * ADDED: Validates if the domain name has proper format
   */
  private static boolean isValidDomainName(String host) {
    // Check for IP addresses (allow them as they're valid hosts)
    if (isIpAddress(host)) {
      return true; // IPs are valid hosts
    }

    // Domain must contain at least one dot for TLD
    if (!host.contains(".")) {
      return false;
    }

    // Must not start or end with dot or hyphen
    if (host.startsWith(".") || host.endsWith(".") ||
            host.startsWith("-") || host.endsWith("-")) {
      return false;
    }

    // Must not contain consecutive dots
    if (host.contains("..")) {
      return false;
    }

    // Use Guava's InternetDomainName for comprehensive validation
    try {
      InternetDomainName domainName = InternetDomainName.from(host);
      // Must have a public suffix (TLD)
      return domainName.hasPublicSuffix();
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * ADDED: Check if host is an IP address
   */
  private static boolean isIpAddress(String host) {
    try {
      InetAddress.getByName(host);
      // If no exception, it's a valid IP
      return host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") || // IPv4
              host.contains(":"); // IPv6 (simplified check)
    } catch (UnknownHostException e) {
      return false;
    }
  }

  private static boolean isLocalhost(String host) {
    return host.equals("localhost") ||
            host.equals("127.0.0.1") ||
            host.equals("::1") ||
            host.startsWith("127.") ||
            host.startsWith("0.");
  }

  private static boolean isPrivateOrReservedIP(String host) {
    // Check for IPv4 private ranges
    for (String prefix : PRIVATE_IP_PREFIXES) {
      if (host.startsWith(prefix)) {
        return true;
      }
    }

    // Additional check using InetAddress for more comprehensive validation
    try {
      InetAddress addr = InetAddress.getByName(host);
      return addr.isLoopbackAddress() ||
              addr.isLinkLocalAddress() ||
              addr.isSiteLocalAddress() ||
              addr.isMulticastAddress();
    } catch (UnknownHostException e) {
      // If we can't resolve it, let it pass this check
      return false;
    }
  }

  private static boolean hasBlockedFileExtension(String path) {
    if (path == null || path.isEmpty()) return false;

    int lastDot = path.lastIndexOf('.');
    if (lastDot > 0 && lastDot < path.length() - 1) {
      String extension = path.substring(lastDot + 1).toLowerCase();
      return BLOCKED_EXTENSIONS.contains(extension);
    }
    return false;
  }

  private static boolean isAllowedPort(int port) {
    // Allow standard HTTP/HTTPS ports and some common web server ports
    return port == 80 || port == 443 || port == 8080 || port == 8443 ||
            (port >= 3000 && port <= 3999) || // Common dev server ports
            (port >= 8000 && port <= 8999);   // Common alt HTTP ports
  }

  /**
   * Result class for URL validation
   */
  public static class ValidationResult {
    private final boolean valid;
    private final String normalizedUrl;
    private final String errorMessage;

    private ValidationResult(boolean valid, String normalizedUrl, String errorMessage) {
      this.valid = valid;
      this.normalizedUrl = normalizedUrl;
      this.errorMessage = errorMessage;
    }

    public static ValidationResult valid(String normalizedUrl) {
      return new ValidationResult(true, normalizedUrl, null);
    }

    public static ValidationResult invalid(String errorMessage) {
      return new ValidationResult(false, null, errorMessage);
    }

    public boolean isValid() { return valid; }
    public String getNormalizedUrl() { return normalizedUrl; }
    public String getErrorMessage() { return errorMessage; }
  }

  public static String extractRootDomain(String host) {
    if (host == null || host.isBlank()) return "";

    try {
      // If host has protocol, extract only host part
      if (host.startsWith("http://") || host.startsWith("https://")) {
        host = new URL(host).getHost();
      }

      // Remove leading dot
      if (host.startsWith(".")) {
        host = host.substring(1);
      }

      // Use Guava to get eTLD+1 (root domain)
      InternetDomainName domainName = InternetDomainName.from(host);
      return domainName.topPrivateDomain().toString();

    } catch (Exception e) {
      return host; // fallback (treat as-is if invalid)
    }
  }
}