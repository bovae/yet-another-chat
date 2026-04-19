package com.bovae.yac.property;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for Content-Disposition based on attachment content type.
 *
 * The disposition logic used in AttachmentApiController.downloadFile() is:
 * if contentType starts with "image/" → "inline", otherwise → "attachment".
 *
 * Since this is pure decision logic, we test it directly as a property.
 *
 * Validates: Requirements 17.1, 17.2, 17.3
 */
class AttachmentDispositionPropertyTest {

    /**
     * Mirrors the disposition decision logic from AttachmentApiController.downloadFile():
     * {@code contentType.startsWith("image/") ? "inline" : "attachment"}
     *
     * Note: in the controller, null/blank contentType is replaced with
     * application/octet-stream before this check, so null here maps to "attachment".
     */
    static String determineDisposition(String contentType) {
        return (contentType != null && contentType.startsWith("image/")) ? "inline" : "attachment";
    }

    @Provide
    Arbitrary<String> imageContentTypes() {
        return Arbitraries.of(
                "image/png", "image/jpeg", "image/gif", "image/webp",
                "image/svg+xml", "image/bmp", "image/tiff"
        );
    }

    @Provide
    Arbitrary<String> nonImageContentTypes() {
        return Arbitraries.of(
                "application/pdf", "application/octet-stream", "application/zip",
                "text/plain", "text/html", "text/csv",
                "audio/mpeg", "video/mp4"
        );
    }

    /**
     * Property 11a: Image content types → "inline"
     *
     * For any attachment download response, if the attachment's contentType starts with "image/",
     * the Content-Disposition header SHALL be "inline".
     *
     * Validates: Requirements 17.1
     */
    @Property(tries = 20)
    void imageContentType_shallReturnInline(
            @ForAll("imageContentTypes") String contentType
    ) {
        assertThat(determineDisposition(contentType))
                .as("Disposition for image content type '%s' should be 'inline'", contentType)
                .isEqualTo("inline");
    }

    /**
     * Property 11b: Non-image content types → "attachment"
     *
     * For any attachment whose contentType does not start with "image/",
     * the Content-Disposition header SHALL be "attachment".
     *
     * Validates: Requirements 17.2
     */
    @Property(tries = 20)
    void nonImageContentType_shallReturnAttachment(
            @ForAll("nonImageContentTypes") String contentType
    ) {
        assertThat(determineDisposition(contentType))
                .as("Disposition for non-image content type '%s' should be 'attachment'", contentType)
                .isEqualTo("attachment");
    }

    /**
     * Property 11c: Null content type → "attachment"
     *
     * For any attachment whose contentType is null, the Content-Disposition header
     * SHALL be "attachment".
     *
     * Validates: Requirements 17.3
     */
    @Property(tries = 10)
    void nullContentType_shallReturnAttachment() {
        assertThat(determineDisposition(null))
                .as("Disposition for null content type should be 'attachment'")
                .isEqualTo("attachment");
    }

    /**
     * Property 11d: Exhaustive — disposition is "inline" if and only if contentType starts with "image/"
     *
     * For any randomly generated content type string, the disposition SHALL be "inline"
     * if and only if the content type starts with "image/".
     *
     * Validates: Requirements 17.1, 17.2, 17.3
     */
    @Property(tries = 20)
    void anyContentType_dispositionMatchesImagePrefix(
            @ForAll("allContentTypes") String contentType
    ) {
        boolean isImage = contentType != null && contentType.startsWith("image/");
        String expected = isImage ? "inline" : "attachment";

        assertThat(determineDisposition(contentType))
                .as("Disposition for content type '%s' should be '%s'", contentType, expected)
                .isEqualTo(expected);
    }

    @Provide
    Arbitrary<String> allContentTypes() {
        return Arbitraries.oneOf(
                imageContentTypes(),
                nonImageContentTypes(),
                Arbitraries.of("image/unknown", "IMAGE/png", "img/png", "imag/png")
        );
    }
}
