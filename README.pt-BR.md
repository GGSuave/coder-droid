# CodeDroid

**Um VS Code completo, terminal Linux, Node.js e Git — rodando 100% local dentro de um único APK Android.**
Sem servidor para configurar, sem sessão do Termux para logar, sem aba de navegador aberta.

> 🇺🇸 [Read in English](README.md)
>
> 🤖 **Feito com o [Claude](https://claude.com/claude-code)** — este projeto inteiro foi desenhado, escrito, depurado e testado pelo Claude (Anthropic) no [Claude Code](https://claude.com/claude-code), dirigido pelo autor por conversa. Veja [Feito com o Claude](#feito-com-o-claude).

---

## O que é?

Celulares e tablets Android são potentes, mas o "terminal" nativo não instala nem roda ferramentas de
desenvolvimento de verdade. O CodeDroid embute um userland Linux completo e o editor VS Code em um app só, para você
abrir uma pasta, editar código com extensões, rodar `npm`, `git` e servidores de desenvolvimento, tudo no próprio aparelho, offline.

Você toca no ícone e cai no VS Code. Só isso.

## Destaques

- **VS Code de verdade** ([code-server](https://github.com/coder/code-server)): extensões (Open VSX), terminal integrado, painel do Git, busca, interface de depuração.
- **Um userland Ubuntu 24.04 real** com `apt`: `apt install openjdk-21-jdk`, `python3`, `build-essential`… funcionam.
- **Node.js (LTS) + npm, Git e GitHub CLI (`gh`)** já instalados. Verificado: `npm create vite` → `npm install` → servidor de desenvolvimento respondendo `HTTP 200`.
- **Pensado para toque e para tablet**: sempre na horizontal (os dois lados, como jogo), barra de teclas (Copiar, Colar, Esc, Tab, Ctrl-C, setas…) que só aparece com o teclado virtual e sem teclado físico, copiar/colar funcionando.
- **Links abrem onde você espera**: um link `localhost:3000` clicado no terminal abre no **Chrome**, não dentro do editor.
- **Lembra da sua pasta** e reabre nela.
- **Comporta-se como um bom app Android**: fechar a janela no gerenciador de janelas para o servidor, todos os terminais e todos os servidores de desenvolvimento. A notificação tem botão *Parar*. Ícone adaptativo com versão temática (Android 13+).
- **Resiliente**: se o Android matar o processo de renderização da WebView (comum ao trocar para o navegador), o app reconstrói a página e reconecta aos seus terminais que continuam rodando, em vez de fechar.
- **Autocontido**: editor, Node, `gh` e certificados estão dentro do APK. Só o `git` (via `apt`) precisa de internet, uma vez, na primeira execução.

## Como funciona

```
┌──────────────────────────── App Android  (dev.codedroid) ────────────────────────────┐
│                                                                                       │
│  MainActivity ── WebView ───────────────▶  http://127.0.0.1:18080   (a UI do VS Code) │
│     ├ ClipboardBridge   (navigator.clipboard ⇄ área de transferência do Android)      │
│     ├ roteador de links (localhost / links externos → Chrome)                         │
│     └ barra de teclas, orientação, pasta lembrada, recuperação de queda do renderer   │
│                                                                                       │
│  ServerService  (serviço em primeiro plano, wake lock, notificação)                   │
│     └─ inicia ▶  libproot.so            ← executado a partir de nativeLibraryDir       │
│                    └─ rootfs/   (Ubuntu 24.04, na pasta privada de arquivos do app)   │
│                         ├ /opt/code-server/     servidor do VS Code (HTTP, local)      │
│                         ├ /usr/local/bin/       node  npm  npx  gh                     │
│                         ├ /usr/bin/git          instalado com apt na 1ª execução       │
│                         └ /root/workspace       seus projetos (mantidos em updates)    │
└───────────────────────────────────────────────────────────────────────────────────────┘
```

**Primeira abertura** (`Installer.kt`)
1. Descompacta o Ubuntu base, o code-server e o Node.js embutidos no armazenamento privado do app (sem root).
2. Escreve a configuração mínima que o sandbox precisa (`resolv.conf`, `hosts`, um shim do Node, padrões do VS Code).
3. Acrescenta extras: certificados CA, GitHub CLI, configuração de credenciais do Git, e instala o `git` pelo `apt` se houver internet.

**A cada abertura**
1. O `ServerService` inicia `libproot.so -r rootfs … code-server --bind-addr 127.0.0.1:18080 --auth none` como processo filho.
2. A `MainActivity` consulta `/healthz` e então carrega o editor numa `WebView`.

**Ao fechar a janela**, o `onTaskRemoved` para o servidor e encerra toda a árvore de processos.

### Os problemas do Android que o projeto teve que resolver

| Problema | O que acontece | Solução |
|---|---|---|
| **W^X**: o Android 10+ proíbe executar arquivos da pasta de dados do app com `targetSdk ≥ 29` | `Permission denied` em qualquer binário descompactado | Entregar o `proot` como `lib*.so` para ele ser instalado em `nativeLibraryDir` (o único lugar de onde apps podem executar) e manter `targetSdk = 28`, como o Termux faz. **Consequência: não serve para a Play Store.** |
| Sem root, sem `chroot` | Não dá para rodar um userland Linux normal | **proot** (`chroot` em espaço de usuário via `ptrace`), a build do Termux, que funciona com a política seccomp do Android. |
| O proot depende de `libtalloc.so.2`, mas `jniLibs` só aceita `lib*.so` | O linker não acha a dependência | O `scripts/fetch_assets.py` renomeia a string `DT_NEEDED` no próprio binário (mesmo tamanho). |
| O Android esconde `/proc/cpuinfo` e bloqueia netlink | `os.cpus()` vem vazio, `os.networkInterfaces()` lança erro; webpack/jest/esbuild e o VS Code quebram | `/opt/android-shim.js`, carregado via `NODE_OPTIONS`, corrige o `os` e chama `syncBuiltinESMExports()` para os módulos ES também enxergarem. |
| A WebView não consegue pedir permissão para `navigator.clipboard.readText()` | "Colar" nos menus do VS Code não faz nada | `ClipboardBridge`: um `WebMessageListener` + script de início de documento substituem `navigator.clipboard`, **restritos à origem do editor**, para que iframes sandbox de extensões não leiam sua área de transferência. |
| O VS Code reescreve `localhost:3000` → `/proxy/3000/` no próprio servidor | Clicar num link do servidor de desenvolvimento tira o editor da tela | Navegações do frame principal para `/proxy/<porta>/…` são convertidas de volta para `http://localhost:<porta>/…` e abertas no Chrome. |
| O Android mata o renderizador da WebView com pouca memória; sem tratamento, **ele derruba o app inteiro** | Servidor e terminais morrem enquanto você está no navegador | `onRenderProcessGone` reconstrói a WebView na mesma URL; o servidor não é tocado. |
| A extensão "GitHub" embutida do VS Code abre um modal *"entrar no GitHub"* (`createIfNone: true`) | Modal de login depois de clone / pull | A extensão é removida; o login é `gh auth login` ou token no terminal. O Git já vem configurado para guardar credenciais. |
| `Process.destroy()` não parava os filhos do proot de forma confiável | Processos `node` órfãos depois de fechar | Depois de parar, o serviço percorre o `/proc` e dá `SIGKILL` em todo descendente. |
| Primeira instalação interrompida (bateria acabou, app morto) | O app abre com um rootfs quebrado | `sync` antes de marcar sucesso, e a cada abertura os arquivos essenciais são verificados; instalação quebrada se refaz sozinha. |

## Requisitos

| | |
|---|---|
| Aparelho | Android **8.0+** (API 26). Celulares/tablets **arm64** usam o flavor `arm64`; o flavor `x64` é para emuladores / aparelhos x86_64. |
| Testado em | Android 15 (emulador x86_64) e um celular arm64 real. Outras versões/aparelhos não foram testados. |
| Espaço | ~330 MB do APK e cerca de **1,5 GB** livres depois de descompactar. |
| RAM | 6 GB ou mais recomendado. VS Code + servidores Node não são leves. |
| Máquina de build | JDK 17+ (o JDK do Android Studio serve), Android SDK (plataforma **36**, build-tools), **Python 3.14+** (ou 3.9+ com `pip install zstandard`), internet. |

## Compilar a partir do código

O repositório guarda só o código-fonte (~120 KB). Os binários que entram no APK (Ubuntu, code-server, Node.js, `gh`, proot…) são baixados por um script.

```bash
git clone https://github.com/<voce>/codedroid.git
cd codedroid

# 1. baixa tudo o que o APK embute (≈ 300 MB por arquitetura)
python scripts/fetch_assets.py --abi arm64      # celulares/tablets  (ou: --abi x64 / --abi all)

# 2. gera um APK debug (assinado com a chave de debug; serve para uso pessoal)
./gradlew :app:assembleArm64Debug               # Windows: gradlew.bat ...

# 3. instala
adb install app/build/outputs/apk/arm64/debug/app-arm64-debug.apk
```

Indique o SDK ao Gradle com `ANDROID_HOME`, ou um arquivo `local.properties` com `sdk.dir=/caminho/do/Android/sdk`.

### Um release assinado

```bash
cp keystore.properties.example keystore.properties   # depois edite (esta no .gitignore)
keytool -genkeypair -keystore codedroid-release.jks -alias codedroid -keyalg RSA -keysize 4096 -validity 10000
./gradlew :app:assembleArm64Release
# → app/build/outputs/apk/arm64/release/app-arm64-release.apk
```

> ⚠️ Guarde o `.jks` e as senhas **fora** do repositório e faça backup. Se perder a chave, não dá mais para lançar atualizações que instalem por cima de uma instalação existente.
> Sem o `keystore.properties`, o release sai sem assinatura.

## Usando

- A **primeira abertura** leva cerca de um minuto (descompactar + instalar o `git`). Depois abre em poucos segundos.
- O Android pede **permissão de notificação** (permita, ou você perde o botão *Parar*) e oferece **isenção da otimização de bateria** (aceite, senão o sistema pode parar o servidor em segundo plano).
- **Terminal:** botão do painel no canto superior direito, ou `` Ctrl+` ``. Selecionar um texto já o copia automaticamente.
- **Login no GitHub:** no terminal rode `gh auth login` → *GitHub.com* → *HTTPS* → *Yes* (autenticar o Git) → *Login with a web browser*. Selecione o código de uso único, aperte Enter, o Chrome abre, cole, autorize e volte. Depois disso `git pull`/`push` não pedem senha. Um token de acesso pessoal também funciona: o Git pergunta uma vez e lembra.
- **Mais ferramentas:** é Ubuntu: `apt update && apt install openjdk-21-jdk python3 build-essential`.
- **Fechar o app** pelo gerenciador de janelas para tudo. O botão Voltar só manda para segundo plano e deixa o servidor rodando.
- **Atualizar** o APK (mesma chave de assinatura) mantém o seu `/root` (projetos e configurações). O que você instalou com `apt` mora no sistema base; se uma versão precisar refazer o sistema (`INSTALL_VERSION` em `Env.kt` muda), isso se perde. Faça backup do que importa.

## Estrutura do projeto

```
app/src/main/kotlin/dev/codedroid/
  MainActivity.kt      hospeda a WebView, barra de teclas, roteamento de links, recuperação, última pasta
  ServerService.kt     serviço em primeiro plano: inicia/para o servidor e toda a árvore de processos
  Installer.kt         descompactação inicial, config, extras (gh, certificados, git), mescla de settings
  Proot.kt             monta a linha de comando do proot usada pelo servidor e pelo instalador
  ClipboardBridge.kt   navigator.clipboard ⇄ área de transferência do Android, restrito por origem
  Env.kt               caminhos e constantes (porta 18080, INSTALL_VERSION…)
app/src/main/res/      ícone adaptativo + monocromático, ícone da notificação, tema
scripts/fetch_assets.py   baixa/prepara todos os binários embutidos
```

Dois product flavors do Gradle (`arm64`, `x64`) diferem só nos binários em `app/src/<flavor>/`. Os comentários do código estão em sua maioria em português.

## Notas para desenvolvedores

- Logs: `adb logcat -s codedroid-server codedroid-web` (saída do servidor, ciclo de vida, recuperação do renderizador). Funciona também em release.
- **Só builds debug** expõem ganchos de teste: `adb shell am start -n dev.codedroid/.MainActivity --es js '<javascript>'`, `--es cb_set <texto>`, `--ez cb_get true`, `--es hw 0|1` (simula a checagem de teclado físico) e um `files/launch.sh` que substitui o comando do servidor. Em release eles são ignorados.
- As versões do code-server, Node.js etc. ficam fixadas no topo do `scripts/fetch_assets.py`.

## Solução de problemas

- **O servidor morre em segundo plano / os terminais somem.** O Android 12+ pode limitar processos filhos "fantasma" de apps. Aceite a isenção de bateria e, se persistir, ative *Opções do desenvolvedor → Desativar restrições de processos filhos* (Android 14+) ou, via ADB, `adb shell settings put global settings_enable_monitor_phantom_procs false` (os comandos exatos variam conforme a versão do Android).
- **Tela branca / "WebView falhou repetidamente".** Atualize o *Android System WebView* e o *Chrome* na Play Store.
- **Erro na primeira abertura.** Libere armazenamento e abra de novo; uma instalação interrompida se repara sozinha.
- Qualquer outra coisa: abra uma issue com a saída de `adb logcat -s codedroid-server`.

## Limitações conhecidas e segurança

- **Não serve para a Play Store** (`targetSdk 28`, veja acima). Distribua o APK diretamente.
- **O editor não tem senha.** O code-server escuta em `127.0.0.1:18080` com `--auth none`. Nada fora do aparelho alcança esse endereço, mas **qualquer outro app instalado no aparelho consegue**, e o editor inclui um terminal dentro do sandbox, com seus projetos e qualquer token do Git guardado. Não instale apps não confiáveis junto com este. Uma senha aleatória por instalação injetada na WebView é a correção óbvia e está no roteiro.
- Os processos do sandbox rodam como root *falso* dentro do proot; **não** é root de verdade e não escapa do sandbox do app Android.
- O proot adiciona sobrecarga em trabalho com muitos arquivos (um `npm install` grande é visivelmente mais lento que num PC).
- Os números de desempenho citados foram medidos num emulador x86_64 em um PC; aparelhos reais variam.
- O Marketplace da Microsoft não está disponível (o code-server usa o Open VSX); algumas extensões proprietárias não instalam.

### Ideias de roteiro

Autenticação por instalação · Java opcional pré-instalado · acesso ao armazenamento (`/sdcard`) · vários workspaces · CI que gera um release a partir de tags.

## Feito com o Claude

Este projeto foi criado de ponta a ponta pelo **Claude** (Anthropic, modelo *Claude Sonnet 5.5*) trabalhando no **Claude Code**, numa longa conversa com o autor, que definiu os objetivos, testou em aparelho real e relatou o que quebrava. O Claude escolheu a arquitetura, escreveu o app em Kotlin e as ferramentas, configurou o Android SDK e o emulador, e depurou lendo logs e medindo no emulador.

É um bom exemplo do que o desenvolvimento assistido por IA consegue fazer, e também um lembrete para **revisar e testar código escrito por IA antes de confiar nele**, principalmente nas notas de segurança acima.

## Licença

[MIT](LICENSE) para o código-fonte deste repositório. O APK embute software de terceiros sob outras licenças (inclusive componentes GPL); veja [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

O CodeDroid é um projeto independente e não oficial: sem afiliação com Microsoft, Coder, Termux, Canonical, GitHub ou o projeto Node.js.
