# Telumia TV

![Telumia](https://raw.githubusercontent.com/Pepeu2010/telumia/main/assets/brand/telumia-banner.png)

Cliente nativo Telumia para **Android TV — Kotlin, Compose TV e Media3**, com foco no Brasil, catálogos configuráveis, perfis, biblioteca e reprodução.

Marca e logo próprios. A entrega 0.2.0-alpha.1 acrescenta aliases brasileiros de Iludida, idioma inicial TMDB pt-BR e seleção do corte brasileiro entre fontes de metadados instaladas, preservando os IDs de capítulos. A edição de 78 capítulos exige uma fonte que a ofereça. Fontes que só fornecem 31 episódios não são artificialmente expandidas.

[Downloads e status](https://github.com/Pepeu2010/telumia/releases) · [Especificação e limites](https://github.com/Pepeu2010/telumia/blob/main/docs/TELUMIA.md) · [Roadmap](https://github.com/Pepeu2010/telumia/blob/main/docs/ROADMAP.md).

## Desenvolvimento

O código em desenvolvimento inclui o editor de avatar pessoal no fluxo existente de perfis: biblioteca integrada com 64 ilustrações licenciadas, arquivo local, clipboard com URI de imagem, recorte, zoom e versões PNG otimizadas. Em aparelhos sem seletor de documentos, a escolha usa fotos locais com permissão solicitada somente ao abrir a ação. O armazenamento é privado, separado do cache e isolado por conta e identidade do perfil.

Este incremento passou 111 testes unitários selecionados, quatro testes do decoder Android e cinco testes de interface por resolução em 720p, 1080p e 4K. A validação usa emulador e imagens controladas; não comprova todos os seletores de fabricantes nem desempenho em TV física. O editor está na release pública 0.2.1-alpha.1; a versão 0.2.0 preserva os binários anteriores. [Evidências e pendências](https://github.com/Pepeu2010/telumia/blob/main/docs/PROFILE_STUDIO.md).

```sh
./gradlew :app:assembleFullDebug
```

Use as instruções de configuração local do [projeto central](https://github.com/Pepeu2010/telumia). Não publique arquivos de credenciais ou keystores. A implementação completa continua em execução: a nova marca e a busca Brasil não concluem Profile Studio, cache Auto, timeline, Live TV/EPG, Scene Info ou Phone Remote.

## Licença

[GPL-3.0](LICENSE). Copyrights, autoria e avisos de terceiros preservados. [Créditos do código](FORK_NOTICE.md).
