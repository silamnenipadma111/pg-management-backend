package com.pgmanagement.service;

import com.pgmanagement.config.AppProperties;
import com.pgmanagement.dto.CommonDtos.UploadResponse;
import com.pgmanagement.entity.Enums;
import com.pgmanagement.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileStorageService {

    private static final Set<String> IMAGE_EXT = Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final Set<String> VIDEO_EXT = Set.of("mp4", "webm", "mov");

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
            Path dir = Path.of(appProperties.uploadDir()).toAbsolutePath();
            Files.createDirectories(dir);
            String name = UUID.randomUUID() + "." + ext;
            file.transferTo(dir.resolve(name));
            return new UploadResponse("/uploads/" + name, type);
        } catch (IOException e) {
            throw new IllegalStateException("Could not store file", e);
        }
    }
}
