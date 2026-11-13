package com.almus.appcreatorpro.git

import com.almus.appcreatorpro.service.github.TokenProvider
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitCoreTest {

    // ------------------------------------------------------------------ branch names
    @Test fun validBranchNames() {
        listOf("main", "feature/login", "fix-123", "release_1.0", "a/b/c").forEach { assertNull(it, GitInputValidator.branchName(it)) }
    }

    @Test fun invalidBranchNames() {
        listOf("", " ", "a b", "a..b", "-x", "/x", "x/", "x.", "a//b", "a@{b", "a~1", "a^", "a:b", "a?b", "a*b", "a[b", "a\\b", "HEAD", "@", "x.lock", "a/.hidden", "a/b.lock/c", "x".repeat(101))
            .forEach { assertNotNull("'$it' should be rejected", GitInputValidator.branchName(it)) }
    }

    // ------------------------------------------------------------------ remote URLs
    @Test fun acceptsHttpsRepositoryAddresses() {
        assertNull(GitInputValidator.remoteUrl("https://github.com/octo/hello.git"))
        assertNull(GitInputValidator.remoteUrl("  https://gitlab.com/group/sub/repo  "))
    }

    @Test fun rejectsUnsafeRemoteAddresses() {
        listOf(
            "", "http://github.com/o/r.git", "ssh://git@github.com/o/r.git", "git@github.com:o/r.git", "git://github.com/o/r.git",
            "file:///sdcard/repo", "/sdcard/repo", "ext::sh -c id", "https://user:pw@github.com/o/r.git",
            "https://ghp_abcdefghijklmnopqrstuvwxyz0123456789@github.com/o/r.git", "https://github.com", "https://github.com/",
            "https://localhost/x", "https://github.com/o/r.git?x=1", "https://github.com/o r.git", "https://github.com/o/r.git#frag",
        ).forEach { assertNotNull("'$it' should be rejected", GitInputValidator.remoteUrl(it)) }
    }

    @Test fun rejectionMessageNeverEchoesTheAddress() {
        val msg = GitInputValidator.remoteUrl("https://user:SECRETPW@github.com/o/r.git")!!
        assertFalse(msg.contains("SECRETPW"))
    }

    @Test fun repoNameFromUrl() {
        assertEquals("Hello-World", GitInputValidator.repoNameOf("https://github.com/octo/Hello-World.git"))
        assertEquals("repo", GitInputValidator.repoNameOf("https://github.com/o/repo/"))
    }

    @Test fun pathsInsideTheProjectOnly() {
        assertNull(GitInputValidator.repoPath("app/src/Main.kt"))
        listOf("", "/etc/passwd", "../x", "a/../b", "a//b", ".git/config", "a/.git/x", "a\\b").forEach { assertNotNull(it, GitInputValidator.repoPath(it)) }
    }

    @Test fun identityRules() {
        assertNull(GitInputValidator.identity("Ada", "ada@example.com"))
        assertNotNull(GitInputValidator.identity("", "ada@example.com"))
        assertNotNull(GitInputValidator.identity("Ada <x>", "ada@example.com"))
        assertNotNull(GitInputValidator.identity("Ada", "not-an-email"))
    }

    // ------------------------------------------------------------------ redaction
    @Test fun redactsTokensAndUrlCredentials() {
        val raw = "fatal: https://x-access-token:ghp_abcdefghijklmnopqrstuvwxyz0123456789@github.com/o/r.git failed; Authorization: Bearer abc123 token=zzz999"
        val out = GitRedactor.redact(raw)
        listOf("ghp_abcdefghijklmnopqrstuvwxyz0123456789", "x-access-token:", "abc123", "zzz999").forEach { assertFalse(it, out.contains(it)) }
        assertTrue(out.contains("github.com/o/r.git"))
    }

    @Test fun redactsFineGrainedTokens() {
        assertFalse(GitRedactor.redact("github_pat_11ABCDEFG0123456789_abcdefghijklmnopqrstuvwxyz").contains("11ABCDEFG"))
    }

    @Test fun exceptionMessagesAreRedacted() {
        val e = GitException(GitException.Kind.NETWORK, "failed for https://u:p4ss@github.com/o/r.git")
        assertFalse(e.message!!.contains("p4ss"))
    }

    @Test fun stripUserInfoKeepsTheRest() {
        assertEquals("https://github.com/o/r.git", GitRedactor.stripUserInfo("https://u:p@github.com/o/r.git"))
    }

    // ------------------------------------------------------------------ credentials
    private fun source(token: String? = "tok123") = GitHubTokenCredentialSource(TokenProvider { token })

    @Test fun tokenOnlyForGitHubHttps() {
        assertNotNull(source().credentialsFor("https://github.com/o/r.git"))
        listOf(
            "https://gitlab.com/o/r.git", "http://github.com/o/r.git", "https://github.com.evil.com/o/r.git",
            "https://evil.com/github.com/o/r.git", "https://user@github.com/o/r.git", "ssh://git@github.com/o/r.git", "not a url",
        ).forEach { assertNull(it, source().credentialsFor(it)) }
    }

    @Test fun noTokenMeansAnonymous() {
        assertNull(source(null).credentialsFor("https://github.com/o/r.git"))
        assertNull(source("  ").credentialsFor("https://github.com/o/r.git"))
    }

    @Test fun credentialsNeverPrintTheSecret() {
        assertFalse(GitCredentials("x-access-token", "tok123").toString().contains("tok123"))
    }

    @Test fun providerAnswersOnlyForItsHost() {
        val provider = HostPinnedCredentialsProvider("github.com", GitCredentials("x-access-token", "tok123"))
        val user = CredentialItem.Username()
        val pass = CredentialItem.Password()
        assertFalse(provider.get(URIish("https://evil.example/o/r.git"), user, pass))
        assertNull(user.value)
        assertFalse(provider.get(URIish("http://github.com/o/r.git"), user, pass))
        assertTrue(provider.get(URIish("https://github.com/o/r.git"), user, pass))
        assertEquals("x-access-token", user.value)
        assertEquals("tok123", String(pass.value))
        assertFalse(provider.toString().contains("tok123"))
    }

    // ------------------------------------------------------------------ secret guard
    @Test fun flagsSecretFileNames() {
        listOf("local.properties", "app/release.jks", "my.keystore", ".env", ".env.production", "keystore.properties", "cert.p12", "server.pem")
            .forEach { assertNotNull(it, GitSecretGuard.checkPath(it)) }
        listOf("app/build.gradle.kts", "README.md", "src/Env.kt", "environment.txt").forEach { assertNull(it, GitSecretGuard.checkPath(it)) }
    }

    @Test fun flagsSecretContentWithoutRevealingIt() {
        val token = "ghp_" + "a".repeat(36)
        val findings = GitSecretGuard.scanText("a.txt", "x = \"$token\"\n-----BEGIN RSA PRIVATE KEY-----\nstorePassword = \"hunter22\"")
        assertEquals(3, findings.size)
        findings.forEach { assertFalse(it.reason.contains(token)); assertFalse(it.reason.contains("hunter22")) }
        assertTrue(GitSecretGuard.scanText("a.kt", "val x = 1\nstorePassword = System.getenv(\"P\")").isEmpty())
    }

    // ------------------------------------------------------------------ conflicts
    @Test fun countsConflictRegions() {
        val text = "a\n<<<<<<< HEAD\nmine\n=======\ntheirs\n>>>>>>> feature\nb\n<<<<<<< HEAD\nx\n=======\ny\n>>>>>>> feature\n"
        assertEquals(2, GitConflicts.countRegions(text))
        assertEquals(0, GitConflicts.countRegions("plain\n=======\nheading\n"))
        assertEquals(1, GitConflicts.countRegions("<<<<<<< HEAD\nunfinished"))
        assertTrue(GitConflicts.hasMarkers("<<<<<<< a\r\nx\r\n=======\r\ny\r\n>>>>>>> b\r\n"))
    }

    // ------------------------------------------------------------------ risks
    @Test fun deleteRiskLevels() {
        val merged = GitRiskAssessor.deleteBranch(BranchDeletePreview("f", false, true, 0, true))
        assertEquals(GitRiskLevel.CONFIRM, merged.level)
        val unmerged = GitRiskAssessor.deleteBranch(BranchDeletePreview("f", false, false, 3, false))
        assertEquals(GitRiskLevel.TYPE_TO_CONFIRM, unmerged.level)
        assertEquals("f", unmerged.requiredText)
        assertFalse(GitConfirmation(true).satisfies(unmerged))
        assertFalse(GitConfirmation(true, "g").satisfies(unmerged))
        assertTrue(GitConfirmation(true, " f ").satisfies(unmerged))
        assertFalse(GitConfirmation(false, "f").satisfies(unmerged))
        assertTrue(GitConfirmation.Yes.satisfies(merged))
    }

    @Test fun secretRiskListsFilesNotValues() {
        val r = GitRiskAssessor.commitWithSecrets(listOf(GitSecretGuard.Finding(".env", "usually holds secrets")))
        assertTrue(r.message.contains(".env"))
    }
}
