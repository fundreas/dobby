# Memo Sock

The memo sock is about saving any memos from the user and then also remember the user of open memos.


## Create Memo
To create a memo: the user just says something like "Erstelle Memo <some text>". It then creates a new memo with a timestamp.


## Latest Memo
It says the latest memo + when there are more open memos: how many other memos are open. The command for it is something like "Gibt es memos", "Liste die Memos", "Welche Memos sind offen".

## Next Memo
It then goes to the next memo and says its content. if other memos are open, it says how many other memos are open. Command: "Nächstes Memo", "Memo Weiter".

## Previous Memo
Basically the same as next-memo, but in other direction.


## Oldest Memo
like latest memo, but it gives the oldest one. next-memo here goes to the next oldest (so bottom-up); previous-memo the other way around.

## Close Memo
The last memo mentioned will be closed (so not active anymore). This only works when before the Select-Memo command was triggered (so a memo-session is active). Memo session closes after 5 minutes again -> so the close memo would not trigger anything.