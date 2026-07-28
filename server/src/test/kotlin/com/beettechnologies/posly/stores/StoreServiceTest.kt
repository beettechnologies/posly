package com.beettechnologies.posly.stores

import com.beettechnologies.posly.TestDatabase
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun pngBytes(): ByteArray {
    val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
    val output = ByteArrayOutputStream()
    ImageIO.write(image, "png", output)
    return output.toByteArray()
}

class StoreServiceTest {

    @BeforeTest
    fun resetDb() {
        TestDatabase.reset()
    }

    private fun seedStore(service: StoreService, locale: String = "en-US"): String {
        val result = service.createStore(
            name = "Downtown",
            address = Address(line1 = "1 Main St", city = "New York", postalCode = "10001", country = "US"),
            timezone = "America/New_York",
            currency = "USD",
            taxProfileId = null,
            locale = locale
        )
        return (result as CreateStoreResult.Created).store.id
    }

    @Test
    fun `createStore defaults locale to en-US when not specified`() {
        val service = StoreService(TaxProfileService())
        val storeId = seedStore(service)
        assertEquals("en-US", service.getStore(storeId)?.locale)
    }

    @Test
    fun `createStore accepts a custom locale tag`() {
        val service = StoreService(TaxProfileService())
        val storeId = seedStore(service, locale = "de-DE")
        assertEquals("de-DE", service.getStore(storeId)?.locale)
    }

    @Test
    fun `createStore rejects a malformed locale tag`() {
        val service = StoreService(TaxProfileService())
        val result = service.createStore(
            name = "Bad",
            address = Address(line1 = "1 Main St", city = "NY", postalCode = "10001", country = "US"),
            timezone = "America/New_York",
            currency = "USD",
            taxProfileId = null,
            locale = "   "
        )
        assertIs<CreateStoreResult.InvalidLocale>(result)
    }

    @Test
    fun `updateStore changes the locale`() {
        val service = StoreService(TaxProfileService())
        val storeId = seedStore(service)

        val result = service.updateStore(
            id = storeId, name = null, address = null, timezone = null, currency = null,
            taxProfileId = null, locale = "fr-FR"
        )

        val updated = assertIs<UpdateStoreResult.Updated>(result).store
        assertEquals("fr-FR", updated.locale)
    }

    @Test
    fun `updateStore rejects a malformed locale tag`() {
        val service = StoreService(TaxProfileService())
        val storeId = seedStore(service)

        val result = service.updateStore(
            id = storeId, name = null, address = null, timezone = null, currency = null,
            taxProfileId = null, locale = "!!!"
        )
        assertIs<UpdateStoreResult.InvalidLocale>(result)
    }

    @Test
    fun `uploadLogo then getLogo roundtrips the file name and bytes`() {
        val service = StoreService(TaxProfileService())
        val storeId = seedStore(service)
        val bytes = pngBytes()

        val result = service.uploadLogo(storeId, "logo.png", bytes)

        val success = assertIs<UploadLogoResult.Success>(result)
        assertEquals("/stores/$storeId/logo", success.logoUrl)
        val logo = service.getLogo(storeId)
        assertEquals("logo.png", logo?.fileName)
        assertTrue(bytes.contentEquals(logo?.bytes))
    }

    @Test
    fun `uploading a second logo replaces the first rather than appending`() {
        val service = StoreService(TaxProfileService())
        val storeId = seedStore(service)
        service.uploadLogo(storeId, "first.png", pngBytes())

        val secondBytes = pngBytes()
        service.uploadLogo(storeId, "second.png", secondBytes)

        val logo = service.getLogo(storeId)
        assertEquals("second.png", logo?.fileName)
    }

    @Test
    fun `uploadLogo for an unknown store is rejected`() {
        val service = StoreService(TaxProfileService())
        val result = service.uploadLogo("does-not-exist", "logo.png", pngBytes())
        assertIs<UploadLogoResult.StoreNotFound>(result)
    }

    @Test
    fun `uploadLogo rejects bytes that are not a decodable image`() {
        val service = StoreService(TaxProfileService())
        val storeId = seedStore(service)

        val result = service.uploadLogo(storeId, "notanimage.txt", "this is not an image".toByteArray())

        assertIs<UploadLogoResult.InvalidImage>(result)
        assertNull(service.getLogo(storeId))
    }

    @Test
    fun `uploadLogo rejects a file larger than the size cap`() {
        val service = StoreService(TaxProfileService())
        val storeId = seedStore(service)

        val oversized = ByteArray(2 * 1024 * 1024 + 1)
        val result = service.uploadLogo(storeId, "huge.png", oversized)

        assertIs<UploadLogoResult.InvalidImage>(result)
    }

    @Test
    fun `getLogo returns null when nothing has been uploaded`() {
        val service = StoreService(TaxProfileService())
        val storeId = seedStore(service)
        assertNull(service.getLogo(storeId))
    }
}
