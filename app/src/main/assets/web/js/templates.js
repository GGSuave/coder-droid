// Modelos de projeto: devolve { caminho relativo: conteudo }.

const appJava = (pkg) => `package ${pkg};

public class App {

    public static void main(String[] args) {
        System.out.println("Olá, CodeDroid Java!");
        System.out.println("2 + 3 = " + soma(2, 3));
    }

    public static int soma(int a, int b) {
        return a + b;
    }
}
`;

const appTestJava = (pkg) => `package ${pkg};

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AppTest {

    @Test
    void somaFunciona() {
        assertEquals(5, App.soma(2, 3));
    }
}
`;

const gitignore = 'target/\nbuild/\nout/\n.gradle/\n*.class\n.idea/\n';

function maven(name, pkg) {
  const artifact = name.toLowerCase().replace(/[^a-z0-9-]/g, '-');
  const dir = pkg.replace(/\./g, '/');
  return {
    'pom.xml': `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>${pkg}</groupId>
  <artifactId>${artifact}</artifactId>
  <version>1.0-SNAPSHOT</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.release>25</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.11.4</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.5.2</version>
      </plugin>
    </plugins>
  </build>
</project>
`,
    [`src/main/java/${dir}/App.java`]: appJava(pkg),
    [`src/test/java/${dir}/AppTest.java`]: appTestJava(pkg),
    '.gitignore': gitignore,
  };
}

function gradle(name, pkg) {
  const dir = pkg.replace(/\./g, '/');
  return {
    'settings.gradle': `rootProject.name = '${name}'\n`,
    'build.gradle': `plugins {
    id 'application'
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.4'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.withType(JavaCompile).configureEach {
    options.release = 25
    options.encoding = 'UTF-8'
}

application {
    mainClass = '${pkg}.App'
}

tasks.named('test') {
    useJUnitPlatform()
}

// Permite que programas que leem do teclado (Scanner) recebam o que voce digita no Console.
tasks.named('run') {
    standardInput = System.in
}
`,
    [`src/main/java/${dir}/App.java`]: appJava(pkg),
    [`src/test/java/${dir}/AppTest.java`]: appTestJava(pkg),
    '.gitignore': gitignore,
  };
}

function single() {
  return {
    'Main.java': `public class Main {

    public static void main(String[] args) {
        System.out.println("Olá, CodeDroid Java!");
    }
}
`,
  };
}

export function newProjectFiles(type, name, pkg) {
  if (type === 'maven') return maven(name, pkg);
  if (type === 'gradle') return gradle(name, pkg);
  return single();
}
