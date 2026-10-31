# Leitor MP4 (Android)

App Android simples para ouvir ficheiros **MP4** (só o áudio) guardados no celular, com som pelo **Bluetooth** do carro ou da caixinha de som.

## O que faz

- Encontra sozinho todos os MP4 da memória do celular (e cartão SD), em qualquer pasta
- Três separadores: **Faixas** (todas), **Pastas** (navegar pelas pastas do celular) e **Playlists**
- **Pastas:** entra e sai das pastas como num explorador de ficheiros; "Tocar esta pasta" toca tudo o que está dentro (incluindo subpastas)
- **Playlists:** criadas dentro do app; mantenha o dedo numa faixa ou pasta › "Adicionar à playlist…"; dentro da playlist pode mover faixas para cima/baixo ou removê-las; mantenha o dedo numa playlist para mudar o nome ou apagar
- Pesquisa no separador aberto
- Toca com o ecrã desligado ou com outra app aberta
- **Bluetooth:** o som sai pelo carro/caixinha, e os botões do rádio (▶ ⏸ ⏭ ⏮) controlam o app; o nome da faixa aparece no ecrã do carro, se o rádio mostrar
- Pausa sozinho quando o Bluetooth desliga (ex.: ao desligar o carro) e durante chamadas
- Controlos na notificação e no ecrã bloqueado
- Aleatório e repetir (tudo / uma faixa)
- Ignora o vídeo do MP4 para gastar menos bateria
- Funciona em Android 7.0 ou mais recente

---

## Como gerar o APK

### Opção A — Online pelo GitHub, sem instalar nada (recomendado)

1. Crie uma conta em <https://github.com> (se ainda não tiver).
2. Crie um repositório novo (ex.: `leitor-mp4`), que pode ser **privado**.
3. Envie todos os ficheiros desta pasta para lá (no site: **Add file › Upload files**, arraste o conteúdo da pasta, incluindo a pasta `.github`).
4. Abra o separador **Actions**. A compilação "Compilar APK" começa sozinha e demora cerca de 3 a 5 minutos.
5. Quando ficar verde ✅, abra a execução e descarregue **LeitorMP4-apk** em *Artifacts* (vem em .zip, com o `app-release.apk` dentro).

> Se a pasta `.github` não for enviada pelo upload do site (às vezes as pastas que começam por ponto ficam escondidas), crie o ficheiro à mão: **Add file › Create new file**, nome `.github/workflows/build-apk.yml`, e cole o conteúdo do ficheiro com o mesmo nome desta pasta.

### Opção B — Android Studio

1. Instale o [Android Studio](https://developer.android.com/studio).
2. **File › Open** e escolha esta pasta `LeitorMP4`. Espere a sincronização do Gradle terminar.
3. **Build › Build App Bundle(s) / APK(s) › Build APK(s)**.
4. O APK fica em `app/build/outputs/apk/debug/app-debug.apk`.
   (Ou ligue o celular por USB com a *Depuração USB* ativa e carregue em ▶ Run.)

---

## Instalar no celular

1. Passe o ficheiro `.apk` para o celular (Google Drive, e-mail, WhatsApp para si próprio, cabo USB…).
2. Abra o APK no celular. O Android pede para **permitir instalar apps desta fonte**: aceite.
3. Se o Play Protect avisar que a app é desconhecida, toque em **Instalar mesmo assim** (é normal para apps que não vêm da Play Store).
4. Ao abrir o app pela primeira vez, **permita o acesso a vídeos/áudio** (é assim que ele encontra os MP4) e às **notificações** (para os controlos no ecrã bloqueado).

## Usar no carro

1. Emparelhe o celular com o carro/caixinha por Bluetooth (como faria para chamadas ou música).
2. No carro, escolha a fonte **Bluetooth Audio / BT**.
3. Abra o Leitor MP4 e toque numa faixa. A partir daí pode usar os botões do volante/rádio.

## Onde guardar os MP4

Em qualquer pasta do celular. Organizar por pastas (ex.: `Música/Viagem`) ajuda a pesquisar: escrever "Viagem" na pesquisa mostra só essa pasta, e tocar numa faixa coloca essa lista toda na fila.
Se acabou de copiar ficheiros e eles não aparecem, toque em **Atualizar** (o Android às vezes leva alguns segundos a catalogá-los).

---

## Estrutura do código

| Ficheiro | Função |
|---|---|
| `PlaybackService.kt` | Serviço em segundo plano com ExoPlayer + MediaSession (Bluetooth, carro, notificação) |
| `MainActivity.kt` | Ecrã principal: lista, pesquisa, botões, barra de progresso, permissões |
| `TrackRepository.kt` | Procura os MP4 na memória através do MediaStore |
| `TrackAdapter.kt` | Lista de faixas |
| `.github/workflows/build-apk.yml` | Compila o APK automaticamente no GitHub |

Tecnologia: Kotlin, AndroidX Media3 1.5.1 (ExoPlayer + MediaSession), minSdk 24, compileSdk 35.
