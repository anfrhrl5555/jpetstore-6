/**
 *    Copyright 2010-2017 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.mybatis.jpetstore.web.actions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;

import net.sourceforge.stripes.action.DefaultHandler;
import net.sourceforge.stripes.action.Resolution;
import net.sourceforge.stripes.action.StreamingResolution;

/**
 * WAS에서 외부망(인터넷)으로 직접 HTTP 호출을 수행하는 ActionBean.
 *
 * <p>
 * 호출 URL: {@code /externalCall.action}
 * </p>
 *
 * <p>
 * 이 ActionBean 은 표준 {@link java.net.HttpURLConnection} 으로 외부 호출을 수행한다.
 * Scouter Java Agent 가 {@code HttpURLConnection} 을 자동 후킹(hook_apicall_enabled=true, 기본값)
 * 하므로, 이 호출은 현재 트랜잭션 프로파일 안에 <b>apicall</b> 스텝으로 기록되어 XLOG 에 그대로 표시된다.
 * 별도의 Scouter API 호출이나 의존성 추가는 필요 없다.
 * </p>
 *
 * <p>
 * 기본 호출 대상은 "외부에서 바라본 내 소스 IP" 를 돌려주는 {@code https://httpbin.org/ip} 이다.
 * kube-vip egress 가 정상 동작하면 응답의 origin 값이 파드 IP 가 아니라 LoadBalancer VIP 로 보이므로
 * egress 경로 검증까지 함께 할 수 있다. 대상 주소는 시스템 프로퍼티/환경변수로 교체 가능하다.
 * </p>
 *
 * <ul>
 * <li>시스템 프로퍼티: {@code -Djpetstore.external.url=https://...}</li>
 * <li>환경변수: {@code EXTERNAL_CALL_URL=https://...}</li>
 * </ul>
 */
public class ExternalCallActionBean extends AbstractActionBean {

  private static final long serialVersionUID = 6740123456789012345L;

  /** 기본 외부 호출 대상 (외부에서 바라본 소스 IP 를 JSON 으로 반환). */
  private static final String DEFAULT_URL = "https://httpbin.org/ip";

  /** 연결 타임아웃(ms). WAS 스레드가 외부 지연에 묶이지 않도록 반드시 지정. */
  private static final int CONNECT_TIMEOUT_MS = 3000;

  /** 응답 읽기 타임아웃(ms). */
  private static final int READ_TIMEOUT_MS = 5000;

  /**
   * 외부망으로 GET 요청을 보내고 응답 본문을 그대로 브라우저로 반환한다.
   */
  @DefaultHandler
  public Resolution call() {
    String target = resolveTargetUrl();
    long start = System.currentTimeMillis();

    HttpURLConnection conn = null;
    try {
      URL url = new URL(target);
      // 아래 openConnection / getInputStream 구간을 Scouter 가 apicall 로 캡처한다.
      conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(READ_TIMEOUT_MS);
      conn.setRequestProperty("Accept", "application/json");
      conn.setRequestProperty("User-Agent", "jpetstore-external-call/1.0");
      conn.connect();

      int status = conn.getResponseCode();
      String body = readBody(conn, status);
      long elapsed = System.currentTimeMillis() - start;

      String result = "{\"target\":\"" + target + "\","
          + "\"httpStatus\":" + status + ","
          + "\"elapsedMs\":" + elapsed + ","
          + "\"response\":" + toJsonString(body) + "}";

      return new StreamingResolution("application/json;charset=UTF-8", new StringReader(result));

    } catch (IOException e) {
      long elapsed = System.currentTimeMillis() - start;
      // 외부망 호출 실패도 트랜잭션 프로파일/에러로 남아 XLOG 에서 식별 가능하다.
      String error = "{\"target\":\"" + target + "\","
          + "\"elapsedMs\":" + elapsed + ","
          + "\"error\":" + toJsonString(e.getClass().getSimpleName() + ": " + e.getMessage()) + "}";
      return new StreamingResolution("application/json;charset=UTF-8", new StringReader(error));
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  /** 시스템 프로퍼티 > 환경변수 > 기본값 순으로 호출 대상을 결정. */
  private String resolveTargetUrl() {
    String url = System.getProperty("jpetstore.external.url");
    if (isBlank(url)) {
      url = System.getenv("EXTERNAL_CALL_URL");
    }
    return isBlank(url) ? DEFAULT_URL : url.trim();
  }

  /** 응답 본문 읽기. 2xx 면 InputStream, 그 외엔 ErrorStream 을 읽는다. */
  private String readBody(HttpURLConnection conn, int status) throws IOException {
    InputStream is = (status >= 200 && status < 400) ? conn.getInputStream() : conn.getErrorStream();
    if (is == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    BufferedReader reader = null;
    try {
      // StandardCharsets(Java7+) 대신 "UTF-8" 문자열 사용 → animal-sniffer 1.6 시그니처 통과
      reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line);
      }
    } finally {
      // try-with-resources 를 쓰면 Throwable.addSuppressed(Java7+) 가 생성되어 1.6 게이트에 걸리므로
      // 고전적인 try/finally 로 직접 닫는다.
      if (reader != null) {
        try {
          reader.close();
        } catch (IOException ignore) {
          // close 실패는 무시
        }
      }
    }
    return sb.toString();
  }

  private static boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }

  /** 응답 문자열을 JSON 값으로 안전하게 감싸기 위한 최소한의 escape. */
  private static String toJsonString(String s) {
    if (s == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder(s.length() + 2);
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        default:
          sb.append(c);
      }
    }
    sb.append('"');
    return sb.toString();
  }

}