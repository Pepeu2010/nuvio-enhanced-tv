# Nuvio Enhanced TV

**Uma evolução open source do Nuvio para Android TV, com mais recursos integrados à base nativa.** Fork independente e não oficial de [NuvioMedia/NuvioTV](https://github.com/NuvioMedia/NuvioTV).

Preservamos Kotlin, Jetpack Compose, TV Material 3, Media3, navegação, restauração de foco, seek progressivo e trailer pool existentes. Os sistemas atuais de conta, perfis, biblioteca, progresso, sync e addons Stremio continuam como pontos de integração.

## Download

[Baixar os APKs do Nuvio Enhanced](https://github.com/Pepeu2010/nuvio-enhanced/releases/tag/v0.1.0-alpha.2).

- **APK universal:** use quando não souber a arquitetura; inclui ARM 32/64 bits, x86 e x86_64.
- **APKs por arquitetura:** arquivos menores para arm64-v8a, armeabi-v7a, x86 e x86_64.
- Fontes e checksums SHA-256 acompanham a pré-release.

São builds **Full Debug de desenvolvimento** para Android TV, com ID `io.github.pepeu2010.nuvioenhanced.tv.debug`. Ainda não são pacotes otimizados de produção ou uma interface dedicada a celulares. A alpha.2 contém a fundação 0-C e o incremento 1-A.1 de movimento de navegação por perfil. A versão interna/versionCode ainda é herdada: instale substituindo o build de desenvolvimento anterior.

O APK universal instalou em emulador 1080p e o seletor foi testado por D-pad, incluindo persistência após reinício. A imagem phone Android x86_64 de 16 KB exigiu modo de compatibilidade nativa; o QR de login falhou nesta sessão. Isto não valida Android TV OS, conta/sync/playback ou TV Box física. [Evidências e capturas](https://github.com/Pepeu2010/nuvio-enhanced/blob/main/docs/NATIVE_FOUNDATION.md).

## Melhorias e evolução

A fundação separa identidade, armazenamento e updater do fork, exige assinatura própria explícita para Release e desliga relatórios externos de falhas por padrão. Os diagnósticos de addons/campos Sentry revisados recebem redaction.

O primeiro incremento 1-A.1 amplia a área Aparência existente com **Movimento de navegação** completo, reduzido e desligado, salvo localmente por perfil. Ele controla as transições revisadas entre telas; outros efeitos mantêm suas configurações atuais.

O incremento **1-A.2 em main, ainda fora da alpha.2**, estende a política ao shell, foco dos cards e skeletons. Passaram 15 testes direcionados e os cinco APKs foram compilados. No APK novo, navegação por D-pad e geração de QR foram verificadas em emulador; o erro de provisionamento público foi corrigido no script do workspace central. Login completo, sync, playback e TV Box física ainda precisam de validação. Outros efeitos e a identidade visual completa continuam pendentes.

Estão previstos: experiência cinematográfica própria para TV, melhores fluxos de foco/D-pad, Home/hero/previews aprimorados, Profile Studio, thumbnails reais/filmstrip/bookmarks, Source Intelligence e cache Auto/configurável. Live TV/EPG, Scene Info e Phone Remote entram nas fases posteriores, sem botões falsos ou canais incluídos.

A referência de performance é TV Box de **2 GB de RAM e 1080p**. Suporte e FPS precisam de medição no aparelho, não de inferência a partir da compilação. A fundação passou em 84 testes direcionados; o baseline completo registrou 20 falhas herdadas e 1 teste ignorado. Login/sync/playback e QA em aparelhos reais permanecem pendentes. [Roadmap e evidências](https://github.com/Pepeu2010/nuvio-enhanced).

## Compilar

Use JDK 17, SDK Android e NDK nas versões de [BASELINE.md](https://github.com/Pepeu2010/nuvio-enhanced/blob/main/docs/BASELINE.md), além das propriedades públicas de desenvolvimento indicadas no workspace central.

```powershell
git clone https://github.com/Pepeu2010/nuvio-enhanced-tv.git
cd nuvio-enhanced-tv
git lfs pull
.\gradlew.bat :app:assembleFullDebug
```

No Linux/macOS, use `./gradlew`. Release exige chave/configuração próprias; nenhuma chave, senha ou configuração privada deve ir ao Git. Os APKs gerados ficam em `app/build/outputs/apk/full/debug/`.

Antes de criar arquivos novos, localize e evolua as telas, ViewModels, repositories, models e componentes existentes. Preserve login/sync/addons e os motores atuais. Valide os fluxos por D-pad, inclusive retorno de foco e dialogs.

## Licença e créditos

[GPL-3.0](LICENSE), copyrights e avisos de terceiros preservados; [FORK_NOTICE.md](FORK_NOTICE.md) identifica a derivação. O histórico conserva a documentação original do upstream. Obrigado aos contribuidores do Nuvio.

Não distribuímos canais, listas ou conteúdo protegido. As fontes legítimas são configuradas pelo usuário. Este projeto não é uma distribuição oficial Nuvio nem afirma endosso do NuvioMedia.
