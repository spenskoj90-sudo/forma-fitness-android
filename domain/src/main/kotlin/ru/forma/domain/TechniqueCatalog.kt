package ru.forma.domain

/**
 * Read-only local technique discovery. Never changes the selected profile,
 * a planned workout, its exercise IDs, recorded history, or cloud consent.
 * The exercise text is offline instructional copy, not certified video.
 */
fun findTechniqueExercises(
 catalog:List<Exercise>,
 available:Set<Equipment>,
 includeUnavailable:Boolean=false,
 muscle:Muscle?=null,
 query:String=""
):List<Exercise>{
 val terms=query.trim().lowercase().split(Regex("\\s+")).filter{it.isNotEmpty()}
 return catalog.asSequence()
  .filter{includeUnavailable||it.equipment in available}
  .filter{muscle==null||it.muscle==muscle}
  .filter{exercise->
   val text=listOf(exercise.name,exercise.equipment.label,exercise.muscle.label,
    exercise.cue,exercise.mistakes,exercise.id).joinToString(" ").lowercase()
   terms.all{text.contains(it)}
  }
  .sortedWith(compareBy<Exercise>({it.muscle.ordinal},{it.name},{it.id}))
  .toList()
}
