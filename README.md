# Bullfrog Drums — emulador Android (projeto de estudo)

Emulação funcional do **Erica Synths × Richie Hawtin Bullfrog Drums** (drum machine / sampler / sequenciador CV‑Gate)
para Android, escrita em **Kotlin puro, sem dependências externas** (só o SDK do Android).
O painel é desenhado em Canvas a partir das medidas e cores da foto de referência, e a lógica de botões segue
o *User Manual V2 (May 2026)*, o *Quickstart* e o *Creative Manual*.

> Projeto educacional. "Bullfrog", "Erica Synths" e o logotipo pertencem aos seus titulares; os samples de fábrica
> aqui são **sintetizados proceduralmente** (não são os samples originais).

## Compilar pelo GitHub (gera o APK)

1. Crie um repositório e envie o conteúdo desta pasta (`git init && git add . && git commit -m init && git push`).
2. Abra a aba **Actions → Build APK**. O workflow roda sozinho a cada push (ou clique em *Run workflow*).
3. Ao terminar, baixe o artefato **BullfrogDrums-apk** (`BullfrogDrums-debug.apk` e `BullfrogDrums-release.apk`;
   ambos assinados com a chave de debug, instaláveis via "fontes desconhecidas").
4. Para publicar em *Releases*: crie uma tag `v1.0` (`git tag v1.0 && git push --tags`).

Build local: abra a pasta no Android Studio, ou rode `gradle assembleDebug` (Gradle 8.9, JDK 17).
Não há `gradlew` no repositório; para gerar um, rode `gradle wrapper` uma vez.

## Como usar (gestos)

O app abre em paisagem. Todas as combinações do manual de "segurar X e tocar Y" funcionam com **multitoque**.
Para usar com **um dedo só**, toque no **sapo/alto‑falante**: o LED laranja liga e passa a valer o modo *latch*
(toque longo > 0,35 s numa tecla a "segura"; toque de novo para soltar).

| Controle | Gesto |
|---|---|
| Knobs (LEVEL, PITCH…, VOLUME) | arrastar para cima/baixo |
| Encoder DATA | arrastar = girar (cada ~16 px = 1 clique); toque = apertar; 5 s parado no menu RST = reset de fábrica |
| Botões / teclas de step | toque; segurar = segurar |

Receita rápida (do quickstart): **PLAY** → encoder muda o BPM · segure **TRACK** + tecla 1‑8 escolhe a trilha ·
teclas de step ligam/desligam passos (1º toque = accent, 2º = normal, 3º = apaga) · segure **LAST STEP** + tecla define o
comprimento · **PATT** + tecla carrega padrão (REC + tecla salva) · **KIT** idem · **SAMPLE** + tecla escolhe a pasta
(A–G fábrica, R gravados) e o encoder escolhe o sample · segure um step + gire um knob = *parameter lock* ·
segure **REC** + gire um knob com o sequenciador tocando = automação · **SHIFT**+TRACK = TAP · **SHIFT**+LAST STEP = SCALE ·
**SHIFT**+SHUFFLE = DIRECT · **SHIFT**+MUTE = SOLO · **SHIFT**+RATCHET = ROLL · **SHIFT**+ACCENT = uTIME ·
**SHIFT**+step 16 = CONFIG.
Trilha 8 (**TRACK**+8) = CV/Gate: o encoder escolhe a nota (SHIFT = oitava), apertar o encoder alterna *GAT* (gate), ACCENT = slide.
Um sintetizador de baixo interno "ouve" a trilha CV/Gate (+2 oitavas sobre o CV real, para ficar audível).

## Arquitetura

```
app/src/main/java/com/study/bullfrog/
├── Model.kt         Kit, Pattern, Memory (4 bancos × 16), Settings/menu CONFIG, tabelas (scale, odds, direções)
├── SampleSynth.kt   gerador procedural dos 7 × 32 samples de fábrica (A kick … G cymbals)
├── SampleStore.kt   16 pastas (A‑O + R) × 64; leitor/escritor WAV; samples do usuário em files/SAMPLES/<letra>/<n>.wav
├── Engine.kt        thread de áudio (AudioTrack float 48 kHz), 7 vozes sampler (pitch ±24 st, decay, loop point,
│                    filtro DJ LP/HP SVF + ressonância, drive, pan, start/end/reverse/loop), sequenciador por trilha
│                    (comprimento, divisão, direção, shuffle, uTIME, ratchet, odds, accent, locks, automação, rolls),
│                    sintetizador CV/Gate
├── Controller.kt    lógica do painel: modos, combinações de botões, LEDs, display, pot‑catch, menu de config,
│                    patterns/kits (salvar/recuperar/copiar/limpar/reload), sampling ao vivo
├── Recorder.kt      AudioRecord: SRC/LVL/THR/TIM, gravação por threshold
├── Storage.kt       persistência JSON (estado, patterns, kits, config) + backup/restauração
├── Factory.kt       8 patterns de fábrica (hip‑hop, house, techno, rock, punk, DnB, electro, reggaeton) + 8 kits
├── PanelView.kt     painel desenhado em Canvas (camada estática em bitmap + camada dinâmica) e multitoque
└── MainActivity.kt  ciclo de vida, permissão de microfone, importação de WAV, backup (Storage Access Framework)
.github/workflows/android.yml   CI: setup‑java 17 + Gradle 8.9 → assembleDebug/Release → artefato/Release
```

Fluxo: `PanelView` (toque) → `Controller` (estado + regras do manual) → `Engine` (áudio/sequenciador, sob um `lock`).
O `Controller` só lê/escreve o modelo (`Pattern`/`Kit`); a thread de áudio renderiza blocos de 256 frames.

## O que está implementado × adaptado

Implementado: transporte; BPM; 7 vozes + CV/Gate; step e TAP; LAST STEP/MASTER LENGTH/BARs (até 64 passos);
SCALE e DIRECT (FWD/BWD/PP/PP2/RND); SHUFFLE por trilha; MUTE/SOLO; ACCENT 1/2 passos; RATCHET, ROLL (+CONTROL ALL),
ODDS (frações e %, ODDS invertidos), uTIME; parameter locks, automação (REC + knob), COPY/PASTE de step, CLEAR;
pot‑catch; patterns/kits (4 bancos × 16, KIT LINK, RELOAD, BPM LOAD); SAMPLE, START/END/REVERSE/LOOP e os mapeamentos
lineares START/END/LOOP + TRACK; sampling ao vivo (MIC, nível, threshold, tempo, trim, salvar na pasta R, excluir);
menu CONFIG com os 22 itens; importação de WAV (equivale ao upload por USB); backup/restauração; persistência.

Adaptado/não emulado (não há hardware equivalente no celular): entradas/saídas MIDI DIN/USB, clock analógico e
CLOCK IN/OUT, RESET, saída CV 1 V/Oct real (substituída pelo baixo interno), calibração e DFU. Esses itens aparecem no
menu e guardam o valor, mas não têm efeito. LIN usa a mesma entrada de áudio do Android que MIC; MON apenas guarda o estado.
