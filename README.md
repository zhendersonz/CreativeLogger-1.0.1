# CreativeLogger

Plugin para Paper 1.21.4+ que rastreia, bloqueia e audita itens retirados do modo criativo por staff.

## Funcionalidades

- **Sessões automáticas**: Inicia uma sessão quando um staff entra no criativo/spectator, encerra ao sair
- **Rastreamento de itens**: Todo item pego da aba criativa é registrado na sessão
- **Bloqueio de itens**: Impede staff de pegar itens específicos configurados
- **Lockdown**: Bloqueia todo acesso ao modo criativo para staff
- **Rate Limit**: Limite de itens por minuto para evitar abusos
- **Sistema de Suspeição**: Pontuação automática baseada em padrões de comportamento
- **GUI de consulta**: Interface para visualizar staffs, sessões e itens
- **Comandos bloqueados**: `/give`, `/item`, `/i`, `/kit` e variações são registrados
- **Integração com Discord**: Webhooks para alertas de itens bloqueados e alto valor
- **Proteção anti-drop**: Itens de origem criativa não podem ser dropados
- **Proteção anti-container**: Itens criativos não podem ser colocados em contêineres
- **Rollback**: Comando para remover itens de sessões específicas
- **Suporte a Geyser/Bedrock**: Detecta jogadores Bedrock automaticamente

## Comandos

| Comando | Descrição |
|---------|-----------|
| `/stafflog gui [player]` | Abre a GUI de consulta |
| `/stafflog sessions <player>` | Lista sessões de um jogador |
| `/stafflog session <id>` | Detalhes de uma sessão |
| `/stafflog lock <player>` | Bloqueia um jogador |
| `/stafflog unlock <player>` | Desbloqueia um jogador |
| `/stafflog whitelist add <player> <item>` | Adiciona item à whitelist |
| `/stafflog whitelist remove <player> <item>` | Remove item da whitelist |
| `/stafflog whitelist list` | Lista whitelist |
| `/stafflog lockdown` | Ativa/desativa lockdown |
| `/stafflog reload` | Recarrega config |
| `/stafflog rollback <session> <item>` | Remove itens de uma sessão |

## Permissões

| Permissão | Descrição |
|-----------|-----------|
| `stafflog.staff` | Membros da staff (sessões são criadas automaticamente) |
| `stafflog.admin` | Acesso a comandos administrativos |
| `stafflog.bypass` | Ignora bloqueios e rate limit |
| `stafflog.gui` | Acesso ao `/stafflog gui` |

## Configuração

O arquivo `config.yml` permite configurar:
- Itens bloqueados e de alto valor
- Limite de rate
- Webhooks do Discord
- Lockdown
- Intervalo de backup do banco de dados

## Dependências

- Paper 1.21.4+ (ou fork compatível)
- Java 21+
- (Opcional) Geyser-Spigot para suporte Bedrock

## Compilando

```bash
mvn clean package
```

O JAR estará em `target/CreativeLogger-1.0.0.jar`.
