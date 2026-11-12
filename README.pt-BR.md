# CodeDroid Java

**Uma IDE Java completa rodando 100% local no seu celular ou tablet Android: JDK 25, Maven, Gradle e um servidor de linguagem de verdade, em um único APK.**
Sem servidor para configurar, sem sessão do Termux, sem aba de navegador.

> 🇺🇸 [Read in English](README.md)
>
> 🌿 Esta é a branch **`java-ide`**. O **CodeDroid** original (VS Code dentro de um APK) fica na branch [`main`](https://github.com/GGSuave/coder-droid/tree/main).
>
> 🤖 **Feito com o [Claude](https://claude.com/claude-code)**: desenhado, escrito, depurado e testado pelo Claude (Anthropic) no [Claude Code](https://claude.com/claude-code), dirigido pelo autor por conversa. Veja [Feito com o Claude](#feito-com-o-claude).

---

## O que é?

Uma IDE só para Java, feita para a tela de toque. Você cria um projeto Maven ou Gradle, edita com autocompletar e verificação de erros em tempo real, aperta **▶ Executar** e vê a saída do programa. Tudo, inclusive o compilador, as ferramentas de build e o servidor de linguagem, está dentro do app.

Ela reaproveita a ideia do CodeDroid (um userland Ubuntu completo dentro do app via `proot`), mas em vez do VS Code tem **interface própria**, construída sobre o editor [Monaco](https://microsoft.github.io/monaco-editor/), conversando com o [Eclipse JDT Language Server](https://github.com/eclipse-jdtls/eclipse.jdt.ls).

## Destaques

- **Ferramentas Java de verdade:** **JDK 25** (Temurin), **Maven 3.9**, **Gradle 9.9** e o servidor de linguagem do Eclipse, todos embutidos.
- **Recursos de linguagem (via LSP):** erros de compilação em tempo real, autocompletar com import automático, documentação ao passar o dedo (hover), correções rápidas, ir para a definição (até no código-fonte da JDK), referências, renomear, formatar, organizar imports.
- **Executar / Compilar / Testar / Parar** para Maven, Gradle e arquivos `.java` soltos. A saída vai para um console onde os locais de arquivo são clicáveis.
- **Entrada no console:** programas que leem do teclado (`Scanner`) funcionam: digite no console e aperte Enter.
- **Assistente de projeto:** aplicação Maven ou Gradle com teste JUnit 5, ou um arquivo Java simples.
- **Pensada para toque e tablet:** layout horizontal, barra de símbolos (`{ } ( ) [ ] ; " < >`, setas, copiar/colar…) que só aparece com o teclado virtual e sem teclado físico, copiar/colar funcionando, temas escuro e claro.
- **Painel de problemas, terminal (um comando por vez) e árvore de arquivos** com criar / renomear / excluir.
- **Nenhuma porta de rede.** A interface fala com o app por uma ponte interna da WebView (veja [Segurança](#segurança)).
- **Comporta-se como um bom app Android:** fechar a janela no gerenciador de janelas para o servidor de linguagem e todas as compilações. A notificação tem botão *Parar*.

## Como funciona

```
┌─────────────────────────── App Android  (dev.codedroid.java) ───────────────────────────┐
│                                                                                         │
│  WebView ── https://appassets.androidplatform.net/assets/web/   (HTML/JS, sem servidor) │
│     editor Monaco · árvore · abas · console · problemas · terminal · cliente LSP        │
│        ▲                                                                                │
│        │  WebMessageListener "ideBridge"  (JSON-RPC, restrito à origem do app)          │
│        ▼                                                                                │
│  Backend em Kotlin                                                                      │
│     FileApi      lê/escreve arquivos em /root (caminhos verificados)                    │
│     JobManager   roda mvn / gradle / java, transmite a saída, mata a árvore inteira     │
│     LspSession   roda o JDT language server por stdio e repassa as mensagens            │
│        │                                                                                │
│        └─ inicia ▶ libproot.so   ← executado a partir de nativeLibraryDir (veja abaixo) │
│                       └─ rootfs/   Ubuntu 24.04 na pasta privada do app                 │
│                            ├ /opt/jdk  /opt/maven  /opt/gradle  /opt/jdtls              │
│                            └ /root/projects        seus projetos (mantidos em updates)  │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

**Primeira abertura** (`Installer.kt`): descompacta o Ubuntu base e cada ferramenta (JDK, Maven, Gradle, servidor de linguagem) no armazenamento privado do app, escreve os arquivos de configuração que o sandbox precisa e instala o `git` pelo `apt` se houver internet. Cada componente tem o próprio marcador de versão, então atualizar uma ferramenta não refaz o resto nem apaga o que você instalou.

**Abrindo um projeto:** a interface inicia o servidor de linguagem (`lsp.start`) e fala o Language Server Protocol com ele pela ponte. Um cliente LSP compacto (`js/lsp.js`) liga as respostas do servidor aos provedores do Monaco.

**Executando:** o botão **▶ Executar** salva tudo, escolhe a ferramenta de build e roda, por exemplo, `mvn -q compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=…` ou `gradle -q run`, transmitindo a saída para o console.

### Os problemas do Android que o projeto teve que resolver

| Problema | Solução |
|---|---|
| **W^X**: o Android 10+ proíbe executar arquivos da pasta de dados do app (com `targetSdk ≥ 29`) | O `proot` vai como `lib*.so` para cair em `nativeLibraryDir`, o único lugar de onde apps podem executar; `targetSdk = 28`, como o Termux. **Não serve para a Play Store.** |
| Sem root, sem `chroot` | **proot** (a build do Termux), `chroot` em espaço de usuário que funciona com a política seccomp do Android. |
| `proot` com `PROOT_NO_SECCOMP=1` fica ~10× mais lento (`javac` 9,9 s contra 0,8 s) | O app nunca define essa variável; o proot usa a aceleração seccomp. |
| O JDT LS mais recente exige **Java 25**, mas o Ubuntu 24.04 só tem o 21 | Embute o tarball do Temurin 25 em vez de usar o `apt`. |
| `Process.destroy()` não para os filhos do proot | Cada trabalho recebe um marcador `CD_JOB=n` herdado pelos filhos; parar varre o `/proc` e dá `SIGKILL` em tudo que tem o marcador. O serviço também mata a árvore inteira ao fechar a janela. |
| O Android mata o renderizador da WebView sob pouca memória, e isso **derruba o app inteiro** | `onRenderProcessGone` reconstrói a WebView; o servidor de linguagem e as compilações não são tocados. |
| O Monaco 0.57 traz os workers como módulos ES com nomes com hash | O `fetch_assets.py` dá um nome estável ao worker do editor e remove os workers de serviços de linguagem não usados. |
| Um celular na horizontal tem só ~364 px (CSS) de altura | Layout compacto: abas e ações na mesma linha, painel inferior sob demanda e o teclado virtual esconde as barras. |

## Requisitos

| | |
|---|---|
| Aparelho | Android **8.0+** (API 26). Celulares/tablets **arm64** usam o flavor `arm64`; `x64` é para emuladores. |
| Testado em | Android 15 em emulador x86_64. A build arm64 é nova e ainda está sendo testada em aparelho real. |
| Espaço | ~350 MB do APK e cerca de 1 GB depois de descompactar, mais seus projetos e os caches do Maven/Gradle. |
| RAM | 6 GB ou mais recomendado (o servidor de linguagem usa até 1 GB). |
| Máquina de build | JDK 17+ (o do Android Studio serve), Android SDK (plataforma **36**), **Python 3.9+**, internet. |

## Compilar a partir do código

O repositório guarda só o código-fonte (~250 KB). Os binários que entram no APK (Ubuntu, JDK, Maven, Gradle, servidor de linguagem, Monaco, proot) são baixados por um script.

```bash
git clone -b java-ide https://github.com/GGSuave/coder-droid.git
cd coder-droid

# 1. baixa tudo o que o APK embute (≈ 400 MB por arquitetura)
python scripts/fetch_assets.py --abi arm64      # celulares/tablets  (ou: --abi x64 / --abi all)

# 2. gera um APK debug
./gradlew :app:assembleArm64Debug               # Windows: gradlew.bat ...

# 3. instala
adb install app/build/outputs/apk/arm64/debug/app-arm64-debug.apk
```

Indique o SDK ao Gradle com `ANDROID_HOME`, ou um arquivo `local.properties` com `sdk.dir=/caminho/do/Android/sdk`.

### Um release assinado

```bash
cp keystore.properties.example keystore.properties   # depois edite (está no .gitignore)
keytool -genkeypair -keystore codedroid-release.jks -alias codedroid -keyalg RSA -keysize 4096 -validity 10000
./gradlew :app:assembleArm64Release
# → app/build/outputs/apk/arm64/release/app-arm64-release.apk
```

> ⚠️ Guarde o `.jks` e as senhas **fora** do repositório e faça backup: sem a chave não dá para lançar atualizações que instalem por cima de uma instalação existente.

## Usando

1. A **primeira abertura** leva cerca de um minuto. Permita as notificações e aceite a isenção da otimização de bateria (senão o Android pode parar o servidor de linguagem em segundo plano).
2. Toque em **Novo projeto**, escolha *Maven* ou *Gradle*, dê um nome e toque em **Criar**.
3. Edite o código. Os erros aparecem enquanto você digita; o autocompletar abre depois do `.`. **Definição** e **Corrigir** ficam na barra do editor; **Mais ▾** tem renomear, formatar, organizar imports e mais.
4. Toque em **▶ Executar**. O console abre com a saída. Se o programa pede entrada, digite no campo embaixo do console.
5. **✔ Testar** roda os testes JUnit. **🔨 Compilar** gera o projeto. **■ Parar** interrompe o que estiver rodando.
6. A primeira execução do Maven/Gradle precisa de internet para baixar plugins e dependências.

Atualizar o APK (mesma chave de assinatura) mantém o seu `/root` (projetos e caches).

## Estrutura do projeto

```
app/src/main/kotlin/dev/codedroid/java/
  MainActivity.kt   hospeda a WebView (asset loader, ponte), detecta teclado, recupera o renderizador
  IdeBridge.kt      ponte JSON-RPC interface <-> app (arquivos, trabalhos, LSP, área de transferência)
  FileApi.kt        operações de arquivo, limitadas a /root, exclusão sem seguir links simbólicos
  Jobs.kt           JobManager (mvn/gradle/java) e LspSession (JDT LS por stdio)
  Installer.kt      descompactação inicial, componentes versionados, configuração, git via apt
  Proot.kt          monta a linha de comando do proot (JAVA_HOME, PATH, ...)
  Tools.kt          utilitários de árvore de processos (matar por marcador / por descendência)
  IdeService.kt     serviço em primeiro plano; para tudo ao fechar a janela
app/src/main/assets/web/
  index.html, style.css
  js/bridge.js      ponte de chamadas/eventos, trabalhos, área de transferência
  js/editor.js      modelos do Monaco, abas, salvamento automático, copiar/colar
  js/lsp.js         cliente LSP: diagnósticos, completar, hover, definição, renomear, ações de código...
  js/ui.js          árvore, painéis, executar/compilar/testar, projetos, configurações, barra de símbolos
  js/templates.js   modelos de projeto Maven / Gradle / arquivo único
  js/app.js         ponto de entrada
scripts/fetch_assets.py   baixa/prepara todos os binários embutidos
```

Dois product flavors do Gradle (`arm64`, `x64`) diferem só nos binários em `app/src/<flavor>/`. Os comentários do código estão em sua maioria em português.

## Notas para desenvolvedores

- **Builds debug** carregam a interface de `files/webdev/` quando essa pasta existe, para iterar no HTML/JS sem recompilar o APK: copie `app/src/main/assets/web/` para lá com o `adb` e recarregue a página.
- Builds debug também expõem `window.__cdj` (os módulos JS) e `adb shell am start -n dev.codedroid.java/.MainActivity --es js '<javascript>'` para rodar código na página, além de `--es hw 0|1` para simular a checagem de teclado físico. Tudo isso é ignorado em release.
- Log do servidor de linguagem: `files/lsp.log` (em debug dá para ler com `adb shell run-as`).
- As versões dos componentes ficam fixadas no topo do `scripts/fetch_assets.py` e no `Installer.kt`.

## Limitações conhecidas

- **Não serve para a Play Store** (`targetSdk 28`). Distribua o APK diretamente.
- **Ainda não há depurador** (breakpoints, passo a passo). É a maior peça faltando (planejado: Java Debug Server + DAP).
- **O terminal roda um comando por vez** (sem programas interativos); ainda não há pty.
- **A edição por toque no Monaco não foi verificada em aparelho real** (alças de seleção, autocorreção/IME).
- O *daemon* do Gradle é encerrado após cada execução para economizar memória, então cada execução do Gradle começa a frio (~12 s no emulador).
- O proot adiciona sobrecarga em trabalho com muitos arquivos (downloads grandes de dependências e builds ficam mais lentos que num PC).
- O `git` é instalado pelo `apt` na primeira execução só se houver internet; senão o app tenta de novo na próxima abertura.

### Segurança

- Nenhuma porta TCP é aberta: a interface alcança o backend só pela ponte da WebView, que aceita mensagens apenas da origem do próprio app.
- O acesso a arquivos é limitado a `/root` dentro do sandbox e recusa caminhos que escapem dele.
- Os processos rodam como root *falso* dentro do proot; **não** é root de verdade e não escapa do sandbox do app Android.

### Roteiro

Depurador · terminal interativo (pty / xterm.js) · painel do Git · outras versões de Java além da 25 · importar projetos do armazenamento.

## Feito com o Claude

Este projeto foi criado de ponta a ponta pelo **Claude** (Anthropic, modelo *Claude Sonnet 5.5*) trabalhando no **Claude Code**, numa longa conversa com o autor, que definiu os objetivos, testou em aparelho real e relatou o que quebrava. O Claude validou a ideia com um teste de viabilidade (JDK + servidor de linguagem dentro do proot), escolheu a arquitetura, escreveu o backend em Kotlin, toda a interface web e as ferramentas de build, e depurou lendo logs e medindo no emulador.

É também um lembrete para **revisar e testar código escrito por IA antes de confiar nele**.

## Licença

[MIT](LICENSE) para o código-fonte deste repositório. O APK embute software de terceiros sob outras licenças (inclusive componentes GPL e EPL); veja [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

O CodeDroid Java é um projeto independente e não oficial: sem afiliação com Oracle, Eclipse Foundation, Microsoft, Termux, Canonical, Apache Software Foundation ou Gradle Inc.
