# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy pom files
COPY pom.xml .
COPY resume-studio-reviewer/pom.xml resume-studio-reviewer/

# Download dependencies
RUN mvn dependency:go-offline -B

# Copy source code
COPY resume-studio-reviewer/src resume-studio-reviewer/src

# Build the application
RUN mvn clean package -DskipTests -pl resume-studio-reviewer

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy the built jar
COPY --from=build /app/resume-studio-reviewer/target/*.jar app.jar

# Expose port
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
