package com.aishots.util;

import com.aishots.exception.ShortsException;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * 파일 경로 보안 유틸리티
 *
 * [보안] Path Traversal 공격 방어
 * jobId나 filename이 "../../../etc/passwd" 같은 값을 포함할 경우
 * 서버의 임의 파일에 접근하거나 덮어쓸 수 있습니다.
 *
 * 방어 전략:
 * 1. 화이트리스트 정규식 — 영문/숫자/하이픈만 허용
 * 2. Path.normalize() 후 baseDir 경계 확인 (startsWith)
 */
public final class PathUtils {

    // jobId: UUID 형식 또는 8자리 hex만 허용
    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9\\-]{1,64}$");

    private PathUtils() {}

    /**
     * ID 값이 안전한지 검증합니다.
     * 영문, 숫자, 하이픈만 허용. 경로 구분자 및 특수문자 차단.
     *
     * @throws ShortsException ID 형식이 유효하지 않을 때
     */
    public static String validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new ShortsException("잘못된 요청입니다.");
        }
        if (!SAFE_ID_PATTERN.matcher(id).matches()) {
            throw new ShortsException("잘못된 요청입니다.");
        }
        return id;
    }

    /**
     * baseDir 안에서만 파일 경로를 생성합니다.
     * normalize() 후 baseDir를 벗어나면 예외를 던집니다.
     *
     * @param baseDir  허용된 기준 디렉토리 (절대 경로 권장)
     * @param filename 파일명 (확장자 포함)
     * @return 검증된 안전한 Path
     * @throws ShortsException 경로가 baseDir를 벗어날 때
     */
    public static Path safeResolve(Path baseDir, String filename) {
        try {
            Path resolved = baseDir.resolve(filename).normalize();
            if (!resolved.startsWith(baseDir.normalize())) {
                throw new ShortsException("잘못된 요청입니다.");
            }
            return resolved;
        } catch (InvalidPathException e) {
            throw new ShortsException("잘못된 요청입니다.");
        }
    }
}
