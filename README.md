# Resume Studio — Backend

Spring Boot 3 service for AI-powered resume review.

---

## Secrets Management

All secrets are **Jasypt-encrypted at rest** and **decrypted at runtime** using a master key you supply as an environment variable. No plaintext secret ever touches disk or git.

### How it works

```
JASYPT_ENCRYPTOR_PASSWORD  ←  only secret you manage
        │
        ▼
application-{local,prod}.properties  ←  ENC(ciphertext) values, safe to commit
        │
        ▼
Spring Boot decrypts at startup  ←  plaintext only in JVM memory
```

### Two env vars to run the app

| Variable | Purpose |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` or `prod` |
| `JASYPT_ENCRYPTOR_PASSWORD` | Master decryption key |

---

## First-time setup

### 1. Choose a strong master key

```bash
# Generate a 32-char random key
openssl rand -base64 32
```

Store it somewhere safe (password manager, secrets vault). **Never commit it.**

### 2. Encrypt each secret

```bash
# Interactive (prompts for master key)
./scripts/encrypt-secrets.sh "your-plaintext-secret"

# Or pass the key via env var
JASYPT_ENCRYPTOR_PASSWORD=mykey ./scripts/encrypt-secrets.sh "your-plaintext-secret"
```

Output: `ENC(AbCdEf...)` — paste this directly into the properties file.

### 3. Fill in the profile files

Edit `resume-studio-reviewer/src/main/resources/application-local.properties` and replace every `ENC(REPLACE_WITH_...)` placeholder with the real encrypted value.

Do the same for `application-prod.properties` with production credentials.

### 4. Run locally

```bash
export SPRING_PROFILES_ACTIVE=local
export JASYPT_ENCRYPTOR_PASSWORD=<your-master-key>
./mvnw spring-boot:run -pl resume-studio-reviewer
```

### 5. Run in production (Docker / CI)

```bash
docker run \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e JASYPT_ENCRYPTOR_PASSWORD=<your-master-key> \
  resume-studio-backend
```

Or in your CI/CD pipeline, inject both env vars as secrets.

---

## Secrets reference

The following properties are encrypted in the profile files:

| Property | Profile file |
|---|---|
| `ai.api.key` | both |
| `supabase.url` | both |
| `supabase.storage.endpoint` | both |
| `spring.datasource.url` | both |
| `spring.datasource.password` | both |
| `spring.data.redis.host` | both |
| `spring.data.redis.password` | both |
| `cloud.aws.credentials.access-key` | both |
| `cloud.aws.credentials.secret-key` | both |

Non-secret config (algorithm tuning, logging, multipart limits) lives in `application.properties` and is committed as-is.

---

## Re-encrypting / rotating the master key

1. Decrypt all values with the old key using `./scripts/encrypt-secrets.sh` (or Jasypt CLI directly).
2. Choose a new master key.
3. Re-encrypt all values with the new key.
4. Update the properties files.
5. Update the master key in your secrets vault / CI environment.
