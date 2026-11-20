package com.pgmanagement.service;

import com.pgmanagement.config.AppProperties;
import com.pgmanagement.dto.CommonDtos.UploadResponse;
import com.pgmanagement.entity.Enums;
import com.pgmanagement.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Stores uploaded PG photos and videos. Files are saved under a random name (the client supplied name is
 * never used as a path) and only when both the extension and the file signature are an allowed media type.
 */
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private static final Set<String> IMAGE_EXT = Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final Set<String> VIDEO_EXT = Set.of("mp4", "webm", "mov");
    private static final Set<String> ISO_MEDIA_BOXES = Set.of("ftyp", "moov", "mdat", "wide", "free", "skip");
    private static final int SIGNATURE_LENGTH = 12;

    private final AppProperties appProperties;

    public UploadResponse store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("File is empty");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        Enums.MediaType type;
        if (IMAGE_EXT.contains(ext)) {
            type = Enums.MediaType.IMAGE;
        } else if (VIDEO_EXT.contains(ext)) {
            type = Enums.MediaType.VIDEO;
        } else {
            throw ApiException.badRequest("Only images (jpg, png, webp, gif) and videos (mp4, webm, mov) are allowed");
        }
        try {
            if (!hasMediaSignature(ext, readSignature(file))) {
                throw ApiException.badRequest("The file content does not look like a ." + ext + " file");
            }
            Path dir = Path.of(appProperties.uploadDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String name = UUID.randomUUID() + "." + ext;
            file.transferTo(dir.resolve(name));
            return new UploadResponse("/uploads/" + name, type);
        } catch (IOException e) {
            throw new IllegalStateException("Could not store file", e);
        }
    }

    private static byte[] readSignature(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(SIGNATURE_LENGTH);
        }
    }

    /** Checks the "magic bytes" at the start of the file against the claimed extension. */
    static boolean hasMediaSignature(String ext, byte[] head) {
        return switch (ext) {
            case "jpg", "jpeg" -> startsWith(head, 0xFF, 0xD8, 0xFF);
            case "png" -> startsWith(head, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
            case "gif" -> ascii(head, 0, 4).equals("GIF8");
            case "webp" -> ascii(head, 0, 4).equals("RIFF") && ascii(head, 8, 4).equals("WEBP");
            case "webm" -> startsWith(head, 0x1A, 0x45, 0xDF, 0xA3);
            case "mp4", "mov" -> ISO_MEDIA_BOXES.contains(ascii(head, 4, 4));
            default -> false;
        };
    }

    private static boolean startsWith(byte[] head, int... signature) {
        if (head.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((head[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(byte[] head, int offset, int length) {
        if (head.length < offset + length) {
            return "";
        }
        return new String(head, offset, length, StandardCharsets.US_ASCII);
    }
}
