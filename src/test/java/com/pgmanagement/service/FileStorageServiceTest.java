package com.pgmanagement.service;

import com.pgmanagement.dto.CommonDtos.UploadResponse;
import com.pgmanagement.entity.Enums;
import com.pgmanagement.exception.ApiException;
import com.pgmanagement.support.TestProps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileStorageServiceTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
    private static final byte[] MP4 = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'};
    private static final byte[] WEBM = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0};
    private static final byte[] TEXT = "<html>not an image</html>".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    private Path uploadDir;

    private FileStorageService service;

    @BeforeEach
    void setUp() {
        service = new FileStorageService(TestProps.app(TestProps.SITE, uploadDir.toString(),
                TestProps.auth("false", 5, true), true));
    }

    @Test
    void storesAnImageUnderARandomName() throws IOException {
        UploadResponse response = service.store(new MockMultipartFile("file", "../../My Photo.PNG", "image/png", PNG));

        assertThat(response.mediaType()).isEqualTo(Enums.MediaType.IMAGE);
        assertThat(response.url()).matches("/uploads/[0-9a-f-]{36}\\.png");
        Path stored = uploadDir.resolve(response.url().substring("/uploads/".length()));
        assertThat(Files.readAllBytes(stored)).isEqualTo(PNG);
    }

    @Test
    void storesAVideo() {
        UploadResponse response = service.store(new MockMultipartFile("file", "tour.mp4", "video/mp4", MP4));

        assertThat(response.mediaType()).isEqualTo(Enums.MediaType.VIDEO);
        assertThat(response.url()).endsWith(".mp4");
    }

    @Test
    void rejectsEmptyAndMissingFiles() {
        MockMultipartFile empty = new MockMultipartFile("file", "a.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> service.store(empty)).isInstanceOf(ApiException.class).hasMessage("File is empty");
        assertThatThrownBy(() -> service.store(null)).isInstanceOf(ApiException.class).hasMessage("File is empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {"malware.exe", "page.html", "noextension", "photo.svg"})
    void rejectsUnsupportedExtensions(String name) {
        MockMultipartFile file = new MockMultipartFile("file", name, "application/octet-stream", PNG);

        assertThatThrownBy(() -> service.store(file)).isInstanceOf(ApiException.class)
                .hasMessageContaining("Only images");
    }

    @Test
    void rejectsContentThatDoesNotMatchTheExtension() throws IOException {
        MockMultipartFile disguised = new MockMultipartFile("file", "photo.png", "image/png", TEXT);

        assertThatThrownBy(() -> service.store(disguised)).isInstanceOf(ApiException.class)
                .hasMessageContaining("does not look like a .png");
        try (Stream<Path> files = Files.list(uploadDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void storageFailuresAreNotSwallowed() throws IOException {
        MultipartFile broken = mock(MultipartFile.class);
        when(broken.getOriginalFilename()).thenReturn("a.png");
        when(broken.getInputStream()).thenThrow(new IOException("disk gone"));

        assertThatThrownBy(() -> service.store(broken)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not store file");
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("signatures")
    void checksMagicBytes(String ext, byte[] head, boolean expected) {
        assertThat(FileStorageService.hasMediaSignature(ext, head)).isEqualTo(expected);
    }

    static Stream<Arguments> signatures() {
        byte[] webp = "RIFF0000WEBP".getBytes(StandardCharsets.US_ASCII);
        byte[] mov = {0, 0, 0, 8, 'w', 'i', 'd', 'e'};
        return Stream.of(
                Arguments.of("png", PNG, true),
                Arguments.of("png", JPG, false),
                Arguments.of("jpg", JPG, true),
                Arguments.of("jpeg", JPG, true),
                Arguments.of("jpg", new byte[]{(byte) 0xFF}, false),
                Arguments.of("gif", "GIF89a".getBytes(StandardCharsets.US_ASCII), true),
                Arguments.of("gif", TEXT, false),
                Arguments.of("webp", webp, true),
                Arguments.of("webp", "RIFF0000WAVE".getBytes(StandardCharsets.US_ASCII), false),
                Arguments.of("webm", WEBM, true),
                Arguments.of("webm", MP4, false),
                Arguments.of("mp4", MP4, true),
                Arguments.of("mov", mov, true),
                Arguments.of("mp4", new byte[]{0, 0}, false),
                Arguments.of("exe", PNG, false));
    }
}
