# Deliberately empty (S1.c.6 step 1): the release build must survive R8 on the default optimized
# rules plus the libraries' own consumer rules, with no app-specific keep. A rule appearing here
# means a library stopped carrying its own, which is the defect to fix instead.
