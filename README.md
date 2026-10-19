# TikTokMobs

Plugin de Paper que convierte los regalos, likes, seguidores y comentarios de un live de
TikTok en mobs y eventos dentro de Minecraft.

Una rosa te da un tótem. Un corazón coreano te suelta creepers. Una galaxia invoca un jefe
con el nombre de quien la mandó. Cada 20 donaciones sube el nivel de caos y todo empieza a
salir multiplicado.

- **Sin mods para quien juega.** Es un plugin de servidor: tus amigos entran con el cliente
  normal, y los de Bedrock entran por Geyser.
- **Sin recompilar para cambiar nada.** El catálogo entero vive en `config.yml`.
- **Sin dependencias.** El jar no empaqueta nada: WebSocket con `java.net.http`, servidor
  HTTP con `com.sun.net.httpserver`, JSON con el Gson que ya trae Paper. Cero conflictos.

---

## Requisitos

| Qué | Versión |
|---|---|
| Servidor | Paper 26.2 (o 1.21.x, ver abajo) |
| Java | 25 para Paper 26.2 · 21 para la rama 1.21 |
| Fuente de eventos | [TikFinity](https://tikfinity.zerody.one/) (app de escritorio, gratis) |
| Opcional | Geyser + Floodgate (jugadores de Bedrock) |
| Opcional | [BetterModel](https://modrinth.com/plugin/bettermodel) (modelos 3D para los jefes) |

---

## Instalación

1. Copia `TikTokMobs.jar` en la carpeta `plugins/` de tu servidor.
2. Arranca el servidor una vez. Se crea `plugins/TikTokMobs/config.yml`.
3. Abre **TikFinity**, inicia sesión con tu cuenta de TikTok y deja la app abierta.
4. En `config.yml` pon `source.type: tikfinity`.
5. `/ttmobs reload`.

Comprueba que está conectado:

```
/ttmobs status
```

Mientras no tengas un live abierto puedes probarlo todo con la fuente falsa
(`source.type: fake`), que siempre está activa:

```
/ttmobs test Rosa 30      simula un combo de 30 rosas
/ttmobs likes 250         inyecta likes
/ttmobs testboss Pedro 500  suelta un jefe
```

---

## Lo que importa del config

### El id manda sobre el nombre

TikTok **traduce el nombre de cada regalo al idioma de quien dona**. La misma rosa llega
como `Rose`, `Rosa` o `Rosa` según la cuenta, así que un mapeo por nombre deja de funcionar
en cuanto alguien dona desde otro idioma. El **id numérico es el mismo siempre**.

Para conseguir los ids: deja que entre un regalo de cada y corre

```
/ttmobs gifts
```

Te lista cada regalo visto con su id, lo que vale y si está mapeado. La consola del
servidor te imprime además un bloque listo para pegar.

```yaml
gift-ids:
  5655:                   # Rosa
    action: give
    item: TOTEM_OF_UNDYING
    per-coin: 1
    max-count: 3
```

> **No pongas ids de ejemplo en ese bloque.** El id gana al nombre, así que un id inventado
> le roba el regalo al mapeo bueno y el síntoma es "ese regalo no hace nada". Y nada de
> referencias YAML (`*algo`) ahí: si el lector del servidor no las resuelve, el mapeo se
> salta en silencio.

### Cascada de resolución

```
id exacto  →  nombre exacto  →  tramo por monedas
```

Los **tramos** (`tiers`) son la red de seguridad: cualquier regalo sin mapeo propio hace algo
proporcional a lo que vale. Funcionan en cualquier idioma y con regalos que TikTok saque
mañana. Si solo configuras una cosa, que sea esa.

### Acumulación

```yaml
count: 2          # suelo: una donación mínima sigue haciendo algo visible
per-coin: 1       # cuántos por moneda donada
max-count: 30     # techo: nadie tumba el servidor con un regalo de 30.000
```

Sin `per-coin`, 7 regalos producen lo mismo que 1 — y para quien donó eso se ve igual que
si no hubiera funcionado.

### Mobs que no se queman

Los tramos usan husk, spider, creeper, enderman, witch, ravager y warden **a propósito**: no
arden de día. Si pones `ZOMBIE` o `SKELETON`, tus donantes verán humo en vez de mobs.

---

## Acciones disponibles

| `action` | Qué hace |
|---|---|
| `spawn` | N mobs alrededor del objetivo |
| `horde` | igual pero en anillo y con más radio |
| `boss` | jefe con vida, equipo y barra propia (ver abajo) |
| `effect` | efecto de poción a todos |
| `give` | un item al jugador |
| `world_event` | uno de los eventos de abajo |
| `none` | nada (para desactivar un mapeo sin borrarlo) |

### Eventos de mundo

| `event` | Qué hace |
|---|---|
| `rayo` | 3 rayos reales junto a ti: daño y fuego |
| `water_drop` | te sube 60 bloques y te pone un balde de agua en la mano (MLG) |
| `nether` | te manda al Nether 60 segundos y te trae de vuelta |
| `suelo_lava` | convierte el suelo a tu alrededor en magma 10 s y lo devuelve |
| `ruleta_rusa` | 1 de 6 te mata — sin perder el inventario |
| `taladro` | 60 segundos minando en 3x3 |
| `ruleta` | sale algo al azar de 12 resultados, mitad premio mitad castigo |
| `tnt_rain` | lluvia de TNT |
| `random_loot` | suelta loot |
| `heal` | curación completa |
| `swarm_bats` | enjambre de murciélagos |
| `night` · `day` · `thunder` | cambian el mundo |

Todo lo que modifica el mundo **se deshace solo**, y también en `onDisable`: si recargas el
plugin con el suelo convertido, los bloques vuelven igual.

---

## El jefe

```yaml
bosses:
  super-boss:
    base-entity: WITHER_SKELETON
    model-id: ""          # id en BetterModel; vacío = entidad vanilla equipada
    hp-base: 40
    hp-per-coin: 0.5
    hp-max: 300
    title-one:  "{donador}, el Implacable"
    title-many: "{donadores}, los Implacables"
    suspense-seconds: 3
    phases:
      minions: { at-health: 0.5, min: 2, max: 3, entity: WITHER_SKELETON }
      rage:    { at-health: 0.2 }
    gear:
      - { min-coins: 0,    weapon: STONE_SWORD,   enchant-level: 1 }
      - { min-coins: 500,  weapon: IRON_SWORD,    helmet: IRON_HELMET,    enchant-level: 2 }
      - { min-coins: 2000, weapon: DIAMOND_SWORD, helmet: DIAMOND_HELMET, enchant-level: 4 }
```

- **Uno a la vez**, y **fuera del tope** de `max-live-spawned`: no lo puede borrar una horda
  que llegue después.
- Otra donación mientras vive **lo enfurece**: más vida y el nombre del nuevo donante pegado
  al anterior (`"Pedro y Ana, los Implacables"`).
- Si el servidor va ahogado, **se encola** y sale cuando el TPS recupera.
- Quién lo pagó va en la **PersistentDataContainer de la entidad**, no en memoria: sobrevive
  a reinicios y a chunks que se descargan. Si te mata él —o su flecha, o uno de sus
  esbirros— te sale `HAS MUERTO / cortesía de Pedro`.

### Modelos 3D (opcional)

Con [BetterModel](https://modrinth.com/plugin/bettermodel) instalado y un `.bbmodel` cargado,
pon su id en `model-id`. Sin el plugin, o con el campo vacío, el jefe es la entidad vanilla
equipada y brillante.

**Los jugadores de Bedrock no verán el modelo**, verán la entidad base. La pelea es idéntica;
solo cambia el aspecto. BetterModel no documenta soporte de Bedrock.

---

## Nivel de caos

```yaml
chaos:
  every-donations: 20
```

Cada N donaciones sube un nivel y todo sale multiplicado, hasta x3. Es **permanente dentro
del live** y se anuncia en el chat cuando sube. Se guarda en `ledger.yml`, así que un
reinicio no lo borra; se limpia con `/ttmobs reset`.

El punto no es el multiplicador: es que le da a la gente una razón para donar que no es
"quiero ver un zombi" sino "quiero ver hasta dónde llega esto".

---

## Chat del live dentro del juego

```yaml
chat:
  enabled: true
  max-per-second: 3
  min-coins: 0        # >0: solo salen los comentarios de quien ya donó
```

Quien escribe en TikTok no ve nunca su mensaje en la transmisión, porque tu pantalla es
Minecraft. En cuanto se ve, escribe más.

El texto se pinta como texto plano: si alguien escribe `&cADMIN: baneado`, sale literal. No
se puede falsificar una línea de chat.

---

## Panel del catálogo

Una página web para configurar qué hace cada regalo, sin tocar el YAML:

```
http://127.0.0.1:8787/panel?token=...
```

El token sale en la consola al arrancar:

```
[panel] configurador del catalogo en http://127.0.0.1:8787/panel?token=a3f9...
```

Tres pestañas:

- **Por id** — los regalos vistos en la sesión y los ya mapeados por id, con su id real.
  Cada fila trae un botón **Probar** que inyecta esa donación para ver qué pasa.
- **Por nombre** — los mapeos por nombre del config, uno por clave (`rosa`, `rose` y `rosas`
  salen como tres filas: agruparlas sería mentir, porque si cambias una y las otras siguen
  apuntando a lo viejo, el regalo hace una cosa u otra según el idioma de quien done).
  Se pueden añadir y borrar.
- **Precios** — la escalera de tramos por monedas, la red de seguridad. Se añaden y borran
  filas, y se guardan ordenadas de barato a caro.

En todas: acción (mobs, horda, jefe, item, evento), qué sale, mínimo, por moneda, máximo y
mensaje. Guardas y el plugin recarga solo, sin reiniciar el servidor.

Los tramos del panel **sustituyen** a los del config, no se suman — si se mezclaran, cada
guardado duplicaría la escalera entera. Y el panel se niega a guardar una escalera vacía:
dejarla sin tramos quitaría la red de todos los regalos sin mapeo propio, que es justo el
fallo más difícil de ver en directo.

Dos decisiones del diseño que conviene conocer:

**El panel escribe `catalogo.yml`, no `config.yml`.** El escritor de YAML de Bukkit no conserva
comentarios, así que guardar sobre el config borraría toda su documentación. El panel tiene su
propio archivo, y lo que hay ahí **manda** sobre el bloque `gift-ids` del config.

**El token no es opcional y se genera solo.** El panel reconfigura y recarga el servidor, y el
puerto del overlay acaba expuesto a internet para que lleguen los relays. Ojo con un detalle
que engaña: cuando el tráfico entra por un túnel (playit, ngrok), el cliente que ve el servidor
es el túnel, que corre en la misma máquina — mirar si viene de `127.0.0.1` **no** distingue
"yo" de "internet". Por eso: token siempre, y distinto del de la browser source de OBS, que
acaba en cualquier captura del directo.

---

## Overlay para OBS

El plugin sirve una página y le empuja los eventos por SSE.

```yaml
overlay:
  enabled: true
  bind: "127.0.0.1"
  port: 8787
  token: ""
```

En OBS: **Browser source**, URL `http://127.0.0.1:8787/`, 1080x1920.
Para ver el diseño sin eventos reales: `http://127.0.0.1:8787/?demo=1`.

> **Si expones ese puerto a internet, pon un token.** El endpoint `/ingest` queda público y
> cualquiera podría inyectar donaciones falsas.

En `overlay-lateral/` hay además un catálogo en regla horizontal (1080x190) pensado para ir
pegado encima de la bandeja de regalos de TikTok, con versión chroma y transparente.

---

## Para tus amigos: las tres formas de entrar

Depende de lo que quieran hacer. Solo la tercera tiene algo de trabajo.

### A) Solo jugar en tu servidor

**No instalan nada.** Abren Minecraft, Multijugador, y ponen la dirección que les pases.
El plugin corre en tu servidor, no en su cliente. Los de Bedrock entran por Geyser.

### B) Jugar Y que sus donaciones cuenten

Para que los regalos de **su** live también suelten mobs en tu mundo:

1. Instalan **TikFinity** y vinculan su cuenta de TikTok.
2. Bajan la carpeta `relay/` de este repo y hacen doble clic en `relay.bat`.
3. Les pide tres datos la primera vez: la dirección de tu servidor, su id de streamer y el
   token. Los tres se los pasas tú.

El relay no instala nada —usa el PowerShell que ya trae Windows— y **solo hace conexiones de
salida**: no abren puertos ni montan túneles. Instrucciones completas en `relay/LEEME.txt`.

Tú tienes que añadirlos a `sources` en tu `config.yml`:

```yaml
sources:
  - id: pana          # el id que le pasas a el
    url: ""           # vacio = modo relay, el empuja hacia ti
    player: "SuNombreEnMinecraft"   # opcional: sus donaciones le salen a EL
```

### C) Montar su propio servidor

Si quieren su live con su mundo, aparte del tuyo:

1. Un servidor **Paper 26.2** con **Java 25**.
2. El `TikTokMobs.jar` de la pestaña **Releases** de este repo, en `plugins/`.
3. Arrancar una vez, poner `source.type: tikfinity` en el config, y `/ttmobs reload`.

A partir de ahí es su servidor y su catálogo: el panel web y todo lo demás funcionan igual.

---

## Varios streamers en el mismo servidor

Tus amigos **no instalan nada del plugin**. Corren el relay de `relay/`, que es un script de
PowerShell: lee el TikFinity de su PC y empuja los eventos a tu servidor. Solo hace
conexiones de salida, así que no abren puertos ni montan túneles.

```yaml
sources:
  - id: main
    url: "ws://127.0.0.1:21213/"   # tu propio TikFinity
    player: ""
  - id: pana
    url: ""                        # vacío = modo relay
    player: "NombreMinecraftDeTuPana"
```

Con `player` puesto, las donaciones de ese live le salen **a él** alrededor.

---

## Comandos

Todos piden el permiso `tiktokmobs.admin` (por defecto, op).

| Comando | Qué hace |
|---|---|
| `/ttmobs status` | estado, cola, TPS, límites, caos |
| `/ttmobs gifts` | regalos vistos con su id — **el más útil** |
| `/ttmobs test <regalo> [cant] [monedas]` | simula una donación |
| `/ttmobs testboss <donante> [monedas] [id]` | suelta un jefe |
| `/ttmobs likes <n>` · `follow` · `share` | inyecta eventos sociales |
| `/ttmobs sources` | lives conectados y su estado |
| `/ttmobs overlay` | URL del overlay |
| `/ttmobs raw` | vuelca el JSON crudo de TikFinity al log |
| `/ttmobs target here` | fija la arena donde estás |
| `/ttmobs coins <n> [donante]` | suma monedas al marcador sin spawnear |
| `/ttmobs clear` | elimina los mobs spawneados |
| `/ttmobs reset` | reinicia monedas, top, likes y caos |
| `/ttmobs reload` | recarga `config.yml` sin reiniciar |

Alias: `/tiktokmobs`, `/ttm`.

---

## Compilar

```bash
./gradlew build
```

El jar sale en `plugin/build/libs/`.

Para la rama antigua de Minecraft:

```bash
./gradlew build -PpaperApi=1.21.11-R0.1-SNAPSHOT -PjavaVersion=21 -PapiVersion=1.21
```

> `api-version` en `plugin.yml` lo rellena Gradle. Un servidor **más viejo** que ese valor se
> niega a cargar el plugin, así que para 1.21.x hay que compilar con `-PapiVersion=1.21`.

---

## Arquitectura

```
GiftSource (hilo del WebSocket)
     │  submit()  ── solo encola, prohibido tocar Bukkit
     ▼
GiftDispatcher (hilo principal, cada tick, con presupuesto y guardia de TPS)
     │  ActionResolver (core: dedup, rachas, tramos, meta, caos)
     ▼
BukkitActionExecutor ──► mundo
```

Dos módulos:

- **`core`** — dominio puro, cero imports de Bukkit. Se testea sin levantar un servidor.
- **`plugin`** — el adaptador de Paper. Es el único que habla con el servidor.

Tres cosas que no se negocian en este proyecto:

1. **Nada de Bukkit fuera del hilo principal.** La API no es thread-safe: spawnear desde el
   hilo del WebSocket corrompe el mundo.
2. **Tres defensas apiladas** contra una avalancha de donaciones: cola acotada (se descarta
   en vez de petar la memoria), presupuesto por tick, y guardia de TPS.
3. **Ningún nick llega a Bukkit sin pasar por `NameSanitizer`.** Es input de un desconocido
   que acaba en un nameplate y en el chat.

---

## Cuando "un regalo no hace nada"

En orden, sin adivinar:

1. La línea del arranque `[config] gift-ids cargados (N): ...` — dice qué cargó de verdad.
2. `/ttmobs gifts` — el id y el nombre real, y si está mapeado.
3. `/ttmobs test <Nombre> 1` — inyecta por nombre con id 0, así se separa si falla el nombre
   o el id.
4. `/ttmobs raw` — el JSON crudo de tu TikFinity.

---

## Assets

Los iconos de los regalos de TikTok y las texturas de los mobs **no están en este repo**: no
son nuestros. Pon los tuyos en `overlay-lateral/iconos/`; donde falte un png, el catálogo
pinta la inicial y no se rompe.

Los que sí están (rayo, water_drop, jefe, random, witch, manzana_dorada, g_seguir) son
dibujos originales hechos para este proyecto.

---

## Licencia

MIT. Ver [LICENSE](LICENSE).
